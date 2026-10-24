package dev.apkcli.core.sched

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.int
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.str
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Work classes. Everything the app does is admitted under exactly one of these.
 *
 * The distinction is "who is waiting", not "how expensive":
 */
enum class TaskClass {
    /** A human is watching a spinner. Opening a file, a grep from the UI. */
    INTERACTIVE,

    /**
     * The agent is working and the work is heavy: commands, builds, packages,
     * fetches. Its threads sit in Android's background cgroup, so quick work
     * a human waits on (a file edit, a store write) does not belong here.
     */
    BACKGROUND,

    /** Nobody asked. Indexing, pruning, FTS optimisation. First to be dropped. */
    BULK,
}

/** Resolved budget for the device's current thermal and power state. */
data class LoadPolicy(
    val interactive: Int,
    val background: Int,
    val bulk: Int,
    val niceBackground: Int,
    val niceBulk: Int,
    val allowIndexing: Boolean,
    val allowBuild: Boolean,
    val bulkPaceMs: Long,
    val thermalStatus: Int,
    val reason: String,
) {
    companion object {
        /** Used before the first probe and when the native core is absent. */
        fun default(cores: Int) = LoadPolicy(
            interactive = cores.coerceIn(1, 4),
            background = (cores / 2).coerceIn(1, 4),
            bulk = 1,
            niceBackground = 4,
            niceBulk = 12,
            allowIndexing = true,
            allowBuild = true,
            bulkPaceMs = 0,
            thermalStatus = 0,
            reason = "default",
        )
    }
}

/**
 * Keeps heavy work from making the editor stutter.
 *
 * Three mechanisms, in increasing order of bluntness:
 *
 *  1. **Separate pools per class**, with `nice` and CPU affinity applied on the
 *     worker threads themselves. Bulk work is pinned to the little cluster, so
 *     an indexing pass physically cannot contend with the UI for a big core.
 *
 *  2. **Admission control.** Each class has a semaphore sized by the current
 *     policy. Resizing happens by swapping the semaphore, not by resizing the
 *     thread pool — threads are expensive to churn and in-flight work keeps its
 *     old permit, which drains naturally.
 *
 *  3. **Refusal.** Above a thermal threshold, builds and indexing are not
 *     slowed down, they are declined, with the reason surfaced to the user and
 *     to the model. A compile on a thermally saturated phone takes longer *and*
 *     makes everything else unusable; starting it is the wrong trade.
 *
 * The policy arithmetic lives in Rust (`sched.rs`) because it needs CPU
 * topology from `/sys`. This class owns the Android-side signals and the pools.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadGovernor(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()

    private val _policy = MutableStateFlow(LoadPolicy.default(cores))
    val policy: StateFlow<LoadPolicy> = _policy.asStateFlow()

    private val _topology = MutableStateFlow<Topology?>(null)
    val topology: StateFlow<Topology?> = _topology.asStateFlow()

    data class Topology(val total: Int, val big: List<Int>, val little: List<Int>)

    /* ------------------------------------------------------------- pools */

    private fun factory(name: String, taskClass: TaskClass, nice: Int): ThreadFactory {
        val counter = AtomicInteger(1)
        return ThreadFactory { runnable ->
            Thread({
                // Applied on the worker thread itself: both setpriority and
                // sched_setaffinity act on the calling thread.
                applyThreadPolicy(taskClass, nice)
                runnable.run()
            }, "apkcli-$name-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
    }

    private fun applyThreadPolicy(taskClass: TaskClass, nice: Int) {
        // Android's own priority API first; it also moves the thread between
        // cpusets on most devices, which plain setpriority does not.
        runCatching {
            Process.setThreadPriority(
                when (taskClass) {
                    TaskClass.INTERACTIVE -> Process.THREAD_PRIORITY_DEFAULT
                    TaskClass.BACKGROUND -> Process.THREAD_PRIORITY_BACKGROUND +
                        Process.THREAD_PRIORITY_LESS_FAVORABLE
                    TaskClass.BULK -> Process.THREAD_PRIORITY_LOWEST
                },
            )
        }
        if (!NativeCore.available) return
        runCatching {
            NativeCore.sched("affinity", jsonOf("class" to taskClass.name.lowercase()))
        }
        if (taskClass != TaskClass.INTERACTIVE) {
            runCatching { NativeCore.sched("nice", jsonOf("value" to nice)) }
        }
    }

    // Interactive and background work grows past its usual size instead of
    // queueing behind a full pool. These pools run blocking native calls, and
    // a fixed pool lost a thread for good to every call that never returned:
    // two `sh_exec`s stuck on a pipe and every store write and tool queued
    // behind them, forever. The semaphores, not the pool size, are what limit
    // concurrency; the pool only has to never run dry.
    private val interactivePool = elasticPool(cores.coerceIn(2, 6), factory("ui", TaskClass.INTERACTIVE, 0))
        .asCoroutineDispatcher()

    private val backgroundPool = elasticPool(cores.coerceIn(2, 6), factory("agent", TaskClass.BACKGROUND, 4))
        .asCoroutineDispatcher()

    /**
     * Work admitted through [admit] runs here, detached from its caller, so
     * that a caller that gives up — a tool call past its deadline — really can.
     */
    private val admitted = CoroutineScope(SupervisorJob() + CoroutineName("governor"))

    private val bulkPool = Executors.newFixedThreadPool(
        2.coerceAtMost(cores),
        factory("bulk", TaskClass.BULK, 14),
    ).asCoroutineDispatcher()

    @Volatile
    private var interactiveGate = Semaphore(_policy.value.interactive)

    @Volatile
    private var backgroundGate = Semaphore(_policy.value.background.coerceAtLeast(1))

    @Volatile
    private var bulkGate = Semaphore(_policy.value.bulk.coerceAtLeast(1))

    fun dispatcherFor(taskClass: TaskClass): CoroutineDispatcher = when (taskClass) {
        TaskClass.INTERACTIVE -> interactivePool
        TaskClass.BACKGROUND -> backgroundPool
        TaskClass.BULK -> bulkPool
    }

    private fun gateFor(taskClass: TaskClass): Semaphore = when (taskClass) {
        TaskClass.INTERACTIVE -> interactiveGate
        TaskClass.BACKGROUND -> backgroundGate
        TaskClass.BULK -> bulkGate
    }

    /**
     * Run [block] under admission control.
     *
     * Interactive work is never gated to zero; bulk work can be, and callers
     * must handle [RefusedByGovernor].
     */
    suspend fun <T> admit(taskClass: TaskClass, block: suspend () -> T): T {
        if (taskClass == TaskClass.BULK && _policy.value.bulk == 0) {
            throw RefusedByGovernor("bulk work suspended: ${_policy.value.reason}")
        }
        // Admission is the permit *and* the pool. This used to take only the
        // permit and run `block` in the caller's context — and the caller is a
        // tool invoked from a turn collected in viewModelScope, so grep, file
        // reads and the native search engine all ran on the main thread, and
        // the class-specific pools with their affinity and nice levels sat
        // idle. Exactly the lag this class exists to prevent.
        //
        // The block runs detached and is awaited, rather than run inside the
        // caller with `withContext`. `withContext` cannot return before its
        // block does, and a block stuck in a blocking call ignores
        // cancellation — so a timeout around it never fired, and the permit
        // it held was lost with it. Awaiting can be abandoned: a cancelled
        // caller cancels the block, gives its permit back at once and moves
        // on, whether or not the block has noticed yet.
        val gate = gateFor(taskClass)
        gate.acquire()
        try {
            val work = admitted.async(dispatcherFor(taskClass)) { block() }
            try {
                return work.await()
            } finally {
                if (work.isActive) work.cancel()
            }
        } finally {
            gate.release()
        }
    }

    class RefusedByGovernor(message: String) : IllegalStateException(message)

    /**
     * Throttle a bulk loop. Call between items; it both paces and yields, so an
     * indexing pass over 40k files does not monopolise its core.
     */
    suspend fun pace() {
        val ms = _policy.value.bulkPaceMs
        if (ms > 0) delay(ms)
    }

    fun allowBuild(): Boolean = _policy.value.allowBuild

    fun buildRefusalReason(): String =
        "Device is under thermal or power pressure (${_policy.value.reason}). " +
            "Heavy compilation is declined to keep the UI responsive; it would also " +
            "run slower while throttled. Retry when the device cools down, or plug in."

    /* ------------------------------------------------------- signal source */

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** The slow poll below. Held so [shutdown] can actually stop it. */
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return // idempotent; start() is called per process
        refreshTopology()
        recompute()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                Log.i(TAG, "thermal status -> $status")
                recompute(thermalOverride = status)
            }
            thermalListener = listener
            runCatching {
                powerManager?.addThermalStatusListener(listener)
            }.onFailure { Log.w(TAG, "thermal listener unavailable: ${it.message}") }
        }

        // Thermal callbacks are the main signal, but battery level and
        // power-save mode change without one, so poll slowly as well. 30s is
        // frequent enough to react and cheap enough to ignore.
        //
        // Runs on the interactive pool rather than the bulk one: bulk is capped
        // at two threads and is exactly the pool that saturates under load, so
        // scheduling the recompute there means the policy stops updating
        // precisely when it is needed most.
        pollJob = scope.launch(dispatcherFor(TaskClass.INTERACTIVE)) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                recompute()
            }
        }
    }

    fun stop() {
        thermalListener?.let { listener ->
            runCatching { powerManager?.removeThermalStatusListener(listener) }
        }
        thermalListener = null
        pollJob?.cancel()
        pollJob = null
    }

    fun shutdown() {
        stop()
        runCatching { interactivePool.close() }
        runCatching { backgroundPool.close() }
        runCatching { bulkPool.close() }
    }

    private fun refreshTopology() {
        if (!NativeCore.available) {
            _topology.value = Topology(cores, (cores / 2 until cores).toList(), (0 until cores / 2).toList())
            return
        }
        runCatching {
            val obj = NativeCore.sched("topology", jsonOf())
            _topology.value = Topology(
                total = obj.int("total", cores),
                big = obj["big"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                } ?: emptyList(),
                little = obj["little"]?.let { el ->
                    (el as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
                } ?: emptyList(),
            )
        }.onFailure { Log.w(TAG, "topology probe failed: ${it.message}") }
    }

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus ?: 0 }.getOrDefault(0)
        } else {
            0
        }

    /**
     * Battery read from the sticky `ACTION_BATTERY_CHANGED` broadcast. Querying
     * the sticky intent avoids keeping a live receiver registered for a value
     * we only need every 30 seconds.
     */
    private fun batteryState(): Pair<Int, Boolean> {
        val intent: Intent? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return 100 to true

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    @Synchronized
    private fun recompute(thermalOverride: Int? = null) {
        val thermal = thermalOverride ?: currentThermalStatus()
        val powerSave = runCatching { powerManager?.isPowerSaveMode ?: false }.getOrDefault(false)
        val (batteryPct, charging) = batteryState()
        val screenOn = runCatching { powerManager?.isInteractive ?: true }.getOrDefault(true)

        val next = if (NativeCore.available) {
            runCatching {
                val obj = NativeCore.sched(
                    "policy",
                    jsonOf(
                        "thermalStatus" to thermal,
                        "powerSave" to powerSave,
                        "batteryPct" to batteryPct,
                        "charging" to charging,
                        "screenOn" to screenOn,
                    ),
                )
                LoadPolicy(
                    interactive = obj.int("interactive", 2),
                    background = obj.int("background", 1),
                    bulk = obj.int("bulk", 0),
                    niceBackground = obj.int("niceBackground", 4),
                    niceBulk = obj.int("niceBulk", 12),
                    allowIndexing = obj.bool("allowIndexing", true),
                    allowBuild = obj.bool("allowBuild", true),
                    bulkPaceMs = obj.long("bulkPaceMs", 0),
                    thermalStatus = thermal,
                    reason = obj.str("reason", "native"),
                )
            }.getOrElse { fallbackPolicy(thermal, powerSave, batteryPct, charging) }
        } else {
            fallbackPolicy(thermal, powerSave, batteryPct, charging)
        }

        if (next != _policy.value) {
            Log.i(
                TAG,
                "policy: interactive=${next.interactive} background=${next.background} " +
                    "bulk=${next.bulk} build=${next.allowBuild} (${next.reason})",
            )
            _policy.value = next
            interactiveGate = Semaphore(next.interactive.coerceAtLeast(1))
            backgroundGate = Semaphore(next.background.coerceAtLeast(1))
            bulkGate = Semaphore(next.bulk.coerceAtLeast(1))
        }
    }

    /** Mirrors `sched.rs::policy_for` closely enough to behave the same. */
    private fun fallbackPolicy(
        thermal: Int,
        powerSave: Boolean,
        batteryPct: Int,
        charging: Boolean,
    ): LoadPolicy {
        var level = thermal
        var reason = "nominal"
        if (powerSave) {
            level = maxOf(level, 2); reason = "power save"
        }
        if (!charging && batteryPct <= 15) {
            level = maxOf(level, 3); reason = "battery critical"
        } else if (!charging && batteryPct <= 30) {
            level = maxOf(level, 2); reason = "battery low"
        }
        if (thermal > 0) reason = "thermal status $thermal"

        return when {
            level <= 0 -> LoadPolicy.default(cores)
            level == 1 -> LoadPolicy.default(cores).copy(bulk = 1, bulkPaceMs = 25, reason = reason)
            level == 2 -> LoadPolicy.default(cores).copy(
                background = 2, bulk = 1, bulkPaceMs = 120, niceBulk = 18,
                allowIndexing = charging, reason = reason,
            )
            level == 3 -> LoadPolicy.default(cores).copy(
                interactive = 2, background = 1, bulk = 0,
                allowIndexing = false, allowBuild = false, reason = reason,
            )
            else -> LoadPolicy.default(cores).copy(
                interactive = 1, background = 0, bulk = 0,
                allowIndexing = false, allowBuild = false,
                reason = "$reason — emergency",
            )
        }.copy(thermalStatus = thermal)
    }

    /** Human-readable line for the status bar. */
    fun describe(): String {
        val p = _policy.value
        return "cpu ${p.interactive}/${p.background}/${p.bulk} · ${p.reason}"
    }

    companion object {
        private const val TAG = "LoadGovernor"
        private const val POLL_INTERVAL_MS = 30_000L

        /** Ceiling for an [elasticPool]: far above any real concurrency here. */
        private const val ELASTIC_MAX_THREADS = 64

        /**
         * [core] threads kept warm, more started when all of them are busy,
         * idle extras retired after half a minute. No queue: a task never
         * waits behind a thread that is not coming back.
         */
        private fun elasticPool(core: Int, factory: ThreadFactory): ExecutorService =
            ThreadPoolExecutor(
                core,
                ELASTIC_MAX_THREADS,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                factory,
            )
    }
}
