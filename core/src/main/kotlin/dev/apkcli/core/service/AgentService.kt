package dev.apkcli.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.R
import dev.apkcli.core.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process working while the app is not on screen: a turn, and what
 * runs in the terminals.
 *
 * Without it a backgrounded app is frozen (the cached apps freezer) or killed,
 * with every command it started: the request already paid for is lost
 * halfway, and a build or a dev server stops where it was. Watching a video
 * while the agent works is an ordinary thing to do on a phone.
 *
 * While anything holds it ([hold]) it keeps three things: the foreground
 * state, which keeps the process off the freezer and low on the killer's
 * list, and keeps its network in Doze; a partial wake lock, renewed while
 * held, so the CPU runs with the screen off; and through Android 13 a Wi-Fi
 * lock, which keeps Wi-Fi out of power save with the screen off (from 14 the
 * system turns that lock into one that works only on screen). The exemption
 * from battery optimisation, which the app asks for ([BatteryExemption]),
 * covers what a vendor's own battery manager adds.
 *
 * It is not a bound service with an API. The UI talks to [AgentRuntime]
 * directly — same process — and the service exists to hold the process's
 * priority and its locks, and to let the user see and stop a turn from the
 * notification shade.
 */
class AgentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var renewal: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC -> sync(startId)

            ACTION_STOP_TURN -> {
                // The notification's stop button. The actual cancellation is the
                // UI's job — it owns the coroutine running the turn — so this
                // only flips the flag it observes.
                _stopRequested.value = true
                Log.i(TAG, "stop requested from the notification")
                // Started by the button alone, with nothing held: not left running.
                if (snapshot() == null) stopSelfResult(startId)
            }
        }
        // Not sticky: a turn that was interrupted by a process death must not be
        // silently restarted. The conversation is persisted, so the user can see
        // where it stopped and continue deliberately.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Match what holds the service now, whatever the intent that asked said:
     * in the foreground with its locks while anything holds it, gone once
     * nothing does. Changes arrive from several threads; reading the state
     * here rather than carrying it in each intent is what keeps them from
     * overtaking each other.
     */
    private fun sync(startId: Int) {
        val now = snapshot()
        // Into the foreground first, whatever the state: a start through
        // startForegroundService that is not answered with it is a crash, and
        // the hold that asked for it may be gone by the time it arrives.
        val shown = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(now?.label ?: IDLE_LABEL, stoppable = now?.turn == true))
        }.onFailure { Log.w(TAG, "could not enter the foreground: ${it.message}") }.isSuccess
        if (now == null) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Only when no newer start is on its way: that one is a hold
            // taken since, which a stop now would leave without the service.
            stopSelfResult(startId)
            return
        }
        if (shown) acquireLocks()
    }

    /* --------------------------------------------------------------- locks */

    /**
     * The wake lock, and the Wi-Fi lock where it still works.
     *
     * The wake lock is leased for [WAKE_LOCK_LEASE_MS] and renewed every
     * [WAKE_LOCK_RENEW_MS] while held, rather than taken once for a fixed
     * time: it used to be 30 minutes, after which a long turn went on with
     * the CPU free to sleep. The lease is the backstop against a leak: a
     * process that stops renewing lets go of it by itself.
     */
    private fun acquireLocks() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        // On a lock that is not reference-counted an acquire moves its
        // timeout out rather than stacking a second hold.
        lock?.let { runCatching { it.acquire(WAKE_LOCK_LEASE_MS) } }
        if (renewal?.isActive != true) {
            renewal = serviceScope.launch {
                while (isActive) {
                    delay(WAKE_LOCK_RENEW_MS)
                    wakeLock?.let { runCatching { it.acquire(WAKE_LOCK_LEASE_MS) } }
                }
            }
        }
        if (wifiLock == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching {
                applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(mode, WIFI_LOCK_TAG)
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { Log.w(TAG, "no Wi-Fi lock: ${it.message}") }.getOrNull()
        }
    }

    private fun releaseLocks() {
        renewal?.cancel()
        renewal = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    /* -------------------------------------------------------- notification */

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent activity",
            // LOW: no sound, no vibration. This notification is a status
            // indicator and a stop button, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the agent or a terminal is working, so it is not interrupted."
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    /** @param stoppable a turn holds the service: the notification can stop it. */
    private fun buildNotification(label: String, stoppable: Boolean): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ApkCLI")
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (stoppable) {
            val stop = PendingIntent.getService(
                this,
                1,
                Intent(this, AgentService::class.java).setAction(ACTION_STOP_TURN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop", stop)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_activity"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "apkcli:work"
        private const val WIFI_LOCK_TAG = "apkcli:work"

        /** How long one acquire of the wake lock lasts, and how often it is renewed while held. */
        private const val WAKE_LOCK_LEASE_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 4 * 60 * 1000L

        /** Shown for the moment between the last hold going and the service stopping. */
        private const val IDLE_LABEL = "Finishing"

        /** The alert channel: what the user has to come back for. Not silent, unlike [CHANNEL_ID]. */
        private const val ALERT_CHANNEL_ID = "agent_attention"
        private const val ALERT_NOTIFICATION_ID = 1002

        private const val ACTION_SYNC = "dev.apkcli.agent.SYNC"
        const val ACTION_STOP_TURN = "dev.apkcli.agent.STOP_TURN"

        /** Held by a running turn, or by /compact. */
        const val HOLD_TURN = "turn"

        /** Held while something runs in a terminal ([dev.apkcli.core.runtime.TerminalSessions.working]). */
        const val HOLD_TERMINALS = "terminals"

        /** What keeps the service up, each with what the notification says for it, in the order taken. */
        private val holds = LinkedHashMap<String, String>()

        private class Snapshot(val label: String, val turn: Boolean)

        private fun snapshot(): Snapshot? = synchronized(holds) {
            if (holds.isEmpty()) return null
            // The turn's words while it holds; what else holds otherwise.
            Snapshot(holds[HOLD_TURN] ?: holds.values.first(), turn = HOLD_TURN in holds)
        }

        /**
         * Keep the service up for [key], the notification saying [label].
         * Again with the same key changes the words.
         */
        fun hold(context: Context, key: String, label: String) {
            val first = synchronized(holds) {
                val idle = holds.isEmpty()
                holds[key] = label
                idle
            }
            // Started only by the first hold; the others find it running,
            // and telling a running service needs no foreground start.
            sync(context, start = first)
        }

        /** [key] no longer needs the service; the last one to let go stops it. */
        fun release(context: Context, key: String) {
            synchronized(holds) { if (holds.remove(key) == null) return }
            sync(context, start = false)
        }

        private fun sync(context: Context, start: Boolean) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_SYNC)
            runCatching { if (start) context.startForegroundService(intent) else context.startService(intent) }
                .onFailure { Log.w(TAG, "could not reach the service: ${it.message}") }
        }

        /** A turn starts: what the notification says while it runs. */
        fun start(context: Context, label: String) = hold(context, HOLD_TURN, label)

        /** What the running turn is doing now. Nothing once it has ended: a late label holds nothing up. */
        fun update(context: Context, label: String) {
            if (synchronized(holds) { HOLD_TURN !in holds }) return
            hold(context, HOLD_TURN, label)
        }

        /** The turn ended. */
        fun finish(context: Context) = release(context, HOLD_TURN)

        private val _stopRequested = MutableStateFlow(false)

        /**
         * Set when the user taps Stop in the notification. The UI observes it
         * and cancels the turn's coroutine; the service cannot do that itself
         * because it does not own the job.
         */
        val stopRequested: StateFlow<Boolean> = _stopRequested.asStateFlow()

        fun consumeStopRequest() {
            _stopRequested.value = false
        }

        /**
         * Tell a user who is away from the app that it needs them: an approval
         * the turn is waiting on, or a turn that ended. The status notification
         * cannot do that — it is silent by design, and a turn blocked on an
         * approval used to wait there, unseen, until the user happened to look.
         *
         * A channel of its own at the default importance, so it sounds the way
         * the user set it up in the system settings. The caller decides whether
         * the user is away; [channelName] and the texts come in the app's own
         * language, which this module cannot resolve. One alert at a time: a
         * newer one replaces the last.
         */
        fun alert(context: Context, channelName: String, title: String, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On every alert: cheap, and it renames the channel when the
                // app's language has changed.
                val channel = NotificationChannel(ALERT_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT)
                runCatching { manager.createNotificationChannel(channel) }
            }
            val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                PendingIntent.getActivity(
                    context,
                    2,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_agent_notification)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            // Without the notification permission this shows nothing; the turn
            // goes on either way.
            runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
        }

        /** Take the alert down: the approval was answered, or the user is back. */
        fun clearAlert(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID) }
        }

        /**
         * Status line for the notification.
         *
         * Single source of truth: the shade and the screen should not be able
         * to disagree about what the agent is doing. Returns null for events
         * that do not change the headline, so the caller can skip the IPC.
         */
        fun labelFor(event: AgentEvent): String? = when (event) {
            is AgentEvent.AssistantStarted -> "Thinking…"
            is AgentEvent.ToolCallStarted -> "Running ${event.name}"
            is AgentEvent.CompactionStarted -> "Compacting the conversation"
            is AgentEvent.SubagentStarted -> "Subagent: ${event.agentId}"
            else -> null
        }

        /**
         * Shown while a tool call is blocked on the approval dialog.
         *
         * Not derived from an event, because approval is requested through
         * [dev.apkcli.core.agent.ApprovalGate] rather than the event flow. It
         * lives here so the shade still gets its wording from one place.
         */
        const val AWAITING_APPROVAL_LABEL = "Waiting for your approval"
    }
}
