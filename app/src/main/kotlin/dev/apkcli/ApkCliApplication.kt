package dev.apkcli

import android.app.Application
import android.os.StrictMode
import android.util.Log
import dev.apkcli.core.AgentRuntime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point.
 *
 * Startup does as little as possible on the main thread. The bootstrap creates
 * a few hundred symlinks and runs an exec probe; the native library load opens
 * a SQLite database. None of that belongs in `onCreate`, so it runs in the
 * background and the UI renders against a "starting up" state until the report
 * arrives.
 */
class ApkCliApplication : Application() {

    /**
     * Application-lifetime scope.
     *
     * SupervisorJob so one failed background task does not cancel the rest, and
     * an explicit handler because an uncaught exception in this scope would
     * otherwise crash the process with no useful attribution.
     */
    val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { context, error ->
                Log.e(TAG, "unhandled failure in $context", error)
            },
    )

    lateinit var runtime: AgentRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        installStrictMode()

        runtime = AgentRuntime(this, appScope)

        appScope.launch {
            val report = runtime.start()
            Log.i(TAG, "startup:\n${report.describe()}")
            startupReport = report
        }
    }

    override fun onTerminate() {
        // Not called on real devices, but it is called under instrumentation,
        // and leaking PTY children between tests is miserable to debug.
        runCatching { runtime.shutdown() }
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Drop the blob store's older entries rather than wait to be killed.
        // Blobs are the largest disposable thing we hold: build logs, fetched
        // pages, file snapshots.
        appScope.launch { runCatching { runtime.store.pruneBlobs(keepBytes = 32L * 1024 * 1024) } }
    }

    /**
     * Result of [AgentRuntime.start], once available.
     *
     * Read by the UI to decide whether to show the normal interface or a
     * diagnostic screen. Null means startup has not finished.
     */
    @Volatile
    var startupReport: AgentRuntime.StartupReport? = null
        private set

    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        // Debug-only, and deliberately not fatal: the PTY and native paths do
        // things StrictMode dislikes by nature, and crashing on them would make
        // the terminal undebuggable. Logged so genuine main-thread IO in UI code
        // still gets noticed.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build(),
        )
    }

    companion object {
        private const val TAG = "ApkCliApplication"
    }
}
