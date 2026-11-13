package dev.apkcli.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import dev.apkcli.AppVisibility
import dev.apkcli.MainActivity
import dev.apkcli.R
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.runtime.TerminalBuffer
import dev.apkcli.ui.locale.AppLocales
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the user's shells ask of the app (TerminalBuffer.Request): `open` a
 * file or a link in another app, `share` files or text, `notify`, `clip` to
 * the clipboard. The shell side is a few functions in the profile (Bootstrap).
 *
 * Opening and sharing start an activity, which Android allows an app only
 * while it is on screen: from the background each becomes a notification to
 * tap. A file goes out through the FileProvider, which serves the projects and
 * the exports directory alone; one from anywhere else is copied to exports
 * first, and exports older than a day are cleared then.
 */
class TerminalActions(
    context: Context,
    private val layout: EnvLayout,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext

    private val notificationIds = AtomicInteger(0)

    /** From the terminal's reader thread. */
    fun handle(request: TerminalBuffer.Request) {
        scope.launch {
            runCatching { perform(request) }.onFailure { Log.w(TAG, "terminal request failed", it) }
        }
    }

    private suspend fun perform(request: TerminalBuffer.Request) {
        when (request) {
            is TerminalBuffer.Request.Clipboard -> withContext(Dispatchers.Main) { copy(request.text) }
            is TerminalBuffer.Request.App -> when (request.verb) {
                "open" -> request.args.firstOrNull()?.let { open(it) }
                "share" -> share(request.args.map(::File))
                "share-text" -> request.args.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { shareText(it) }
                "notify" -> request.args.firstOrNull()?.let { notify(it, request.args.getOrNull(1)) }
                else -> Log.i(TAG, "unknown terminal request ${request.verb}")
            }
        }
    }

    private suspend fun open(target: String) {
        val uri = Uri.parse(target)
        if (uri.scheme?.lowercase() in LINK_SCHEMES) {
            start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), R.string.terminal_tap_to_open, target)
            return
        }
        val file = File(target)
        val served = withContext(Dispatchers.IO) { served(file) } ?: return toast(R.string.terminal_not_a_file, file.name)
        val intent = ProjectDocuments.viewIntent(context, served) ?: return toast(R.string.terminal_not_a_file, file.name)
        start(intent, R.string.terminal_tap_to_open, file.name)
    }

    private suspend fun share(files: List<File>) {
        if (files.isEmpty()) return
        val uris = withContext(Dispatchers.IO) {
            files.map { file -> served(file)?.let(::uriOf) ?: return@withContext null }
        } ?: return toast(R.string.terminal_not_a_file, files.joinToString(", ") { it.name })
        val target = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(ProjectDocuments.viewTypeOf(files.single()))
                .putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // The grant goes through the chooser with the clip: createChooser copies both.
        target.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(chooser(target), R.string.terminal_tap_to_share, files.joinToString(", ") { it.name })
    }

    private fun shareText(text: String) {
        val target = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(chooser(target), R.string.terminal_tap_to_share, text.lineSequence().first().take(40))
    }

    /** Others only: this app takes text and images itself, and would be offered first. */
    private fun chooser(target: Intent): Intent = Intent.createChooser(target, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(localized().getString(R.string.terminal_channel), text))
        // From Android 13 the system says so itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.terminal_copied)
    }

    /**
     * [intent] now, while the app is on screen; from the background, a
     * notification that starts it, which Android allows once the user taps.
     */
    private fun start(intent: Intent, tapText: Int, name: String) {
        if (AppVisibility.onScreen.value) {
            runCatching { context.startActivity(intent) }.onSuccess { return }
                .onFailure { Log.w(TAG, "could not start ${intent.action}", it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationIds.incrementAndGet(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(localized().getString(R.string.terminal_from_shell), localized().getString(tapText, name), pending)
    }

    private fun notify(message: String, title: String?) {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        post(title?.takeIf { it.isNotBlank() } ?: localized().getString(R.string.terminal_channel), message, open)
    }

    private fun post(title: String, text: String, tap: PendingIntent?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Every time: cheap, and it renames the channel when the app's language changed.
            val channel = NotificationChannel(CHANNEL_ID, localized().getString(R.string.terminal_channel), NotificationManager.IMPORTANCE_DEFAULT)
            runCatching { manager.createNotificationChannel(channel) }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.apkcli.core.R.drawable.ic_agent_notification)
            .setContentTitle(title.take(MAX_TITLE))
            .setContentText(text.take(MAX_TEXT))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(MAX_TEXT)))
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        // Without the notification permission this is dropped, as Android has it.
        runCatching { manager.notify(NOTIFICATION_BASE + notificationIds.incrementAndGet() % NOTIFICATIONS_KEPT, notification) }
    }

    /** [file] where the FileProvider serves it: in place under the projects, a copy in exports otherwise. */
    private fun served(file: File): File? {
        if (!file.isFile) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val projects = runCatching { layout.projects.canonicalPath }.getOrNull() ?: return null
        if (canonical.path.startsWith("$projects/")) return canonical
        val exports = File(layout.state, EXPORTS)
        pruneExports(exports)
        val copy = File(File(exports, System.currentTimeMillis().toString()), file.name)
        copy.parentFile?.mkdirs()
        return runCatching { file.copyTo(copy, overwrite = true) }.getOrNull()
    }

    private fun pruneExports(exports: File) {
        val cutoff = System.currentTimeMillis() - EXPORT_KEPT_MS
        exports.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private fun uriOf(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun toast(text: Int, vararg args: Any) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, localized().getString(text, *args), Toast.LENGTH_SHORT).show()
        }
    }

    /** Strings in the app's language, which the application context alone does not have before Android 13. */
    private fun localized(): Context = AppLocales.wrap(context)

    private companion object {
        const val TAG = "TerminalActions"
        const val CHANNEL_ID = "terminal"
        const val NOTIFICATION_BASE = 3000
        const val NOTIFICATIONS_KEPT = 20
        const val MAX_TITLE = 80
        const val MAX_TEXT = 2000
        const val EXPORTS = "exports"
        const val EXPORT_KEPT_MS = 24L * 60 * 60 * 1000

        /** What `open` hands to the system as a link; `file:`, `content:` and `intent:` are not among them. */
        val LINK_SCHEMES = setOf("http", "https", "mailto", "tel", "geo", "market", "sms")
    }
}
