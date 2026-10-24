package dev.apkcli

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.apkcli.ui.locale.AppLocales
import dev.apkcli.ui.root.RootScreen
import dev.apkcli.ui.theme.ApkCliTheme

/**
 * The only Activity.
 *
 * Single-Activity because every screen here shares live state — the running
 * turn, the open editor buffers, the attached terminal sessions. Separate
 * Activities would mean either duplicating that state or hoisting it into
 * singletons anyway, with the added hazard of one Activity being destroyed
 * mid-turn.
 *
 * The manifest declares an unusually wide `configChanges` set so rotating the
 * device or attaching a keyboard does not restart this Activity while a
 * response is streaming. Compose handles the resize itself.
 */
class MainActivity : ComponentActivity() {

    /**
     * Notification permission.
     *
     * The result is ignored: the foreground service that keeps a turn alive
     * starts either way, so a refusal costs the user the progress notification
     * and its Stop button, not the feature. Blocking the app on it, or asking
     * again later, would be worse than that.
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** In-app language on Android 12 and older; see [AppLocales]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent, so the first frame is already laid out behind the
        // system bars rather than jumping once the insets arrive.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        askForNotificationPermission()

        setContent {
            ApkCliTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootScreen()
                }
            }
        }
    }

    /**
     * From API 33 the notification permission is runtime-granted, and without
     * it `startForeground` succeeds while the notification is silently
     * suppressed — leaving the user with a turn running in the background and
     * no way to see or stop it.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
