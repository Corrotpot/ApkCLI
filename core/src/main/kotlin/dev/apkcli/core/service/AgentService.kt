package dev.apkcli.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.apkcli.core.AgentRuntime
import dev.apkcli.core.R
import dev.apkcli.core.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a turn alive while the app is not in the foreground.
 *
 * Without this, backgrounding the app during a turn means the process is
 * frozen (Android 12+ app standby) or killed, and the request that was already
 * paid for is lost halfway through. Reading documentation in a browser while
 * the agent works is a completely normal thing to do on a phone, so this is not
 * an edge case.
 *
 * It is not a bound service with an API. The UI talks to [AgentRuntime]
 * directly — same process — and the service exists purely to hold the process
 * priority and a wake lock for the duration, plus to give the user a way to see
 * and stop a running turn from the notification shade.
 */
class AgentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Working"
                startForeground(NOTIFICATION_ID, buildNotification(label, indeterminate = true))
                acquireWakeLock()
            }

            ACTION_UPDATE -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Working"
                notificationManager()?.notify(
                    NOTIFICATION_ID,
                    buildNotification(label, indeterminate = true),
                )
            }

            ACTION_STOP_TURN -> {
                // The notification's stop button. The actual cancellation is the
                // UI's job — it owns the coroutine running the turn — so this
                // only flips the flag it observes.
                _stopRequested.value = true
                Log.i(TAG, "stop requested from the notification")
            }

            ACTION_FINISH -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // Not sticky: a turn that was interrupted by a process death must not be
        // silently restarted. The conversation is persisted, so the user can see
        // where it stopped and continue deliberately.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    /* ----------------------------------------------------------- wake lock */

    /**
     * Held only while a turn is in flight.
     *
     * A reasoning model can spend minutes before its first token, and if the
     * device suspends the network in that window the stream dies. The timeout is
     * a backstop against a leak, not an expected path.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
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
            description = "Shown while the agent is working so the turn is not interrupted."
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String, indeterminate: Boolean): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP_TURN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ApkCLI")
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setProgress(0, 0, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_activity"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "apkcli:turn"

        /** Backstop against a leaked lock; a turn should never run this long. */
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        const val ACTION_START = "dev.apkcli.agent.START"
        const val ACTION_UPDATE = "dev.apkcli.agent.UPDATE"
        const val ACTION_STOP_TURN = "dev.apkcli.agent.STOP_TURN"
        const val ACTION_FINISH = "dev.apkcli.agent.FINISH"
        const val EXTRA_LABEL = "label"

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

        fun start(context: Context, label: String) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start foreground service: ${it.message}") }
        }

        fun update(context: Context, label: String) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_LABEL, label)
            runCatching { context.startService(intent) }
        }

        fun finish(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_FINISH)
            runCatching { context.startService(intent) }
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
