package dev.apkcli.ui.preview

import android.graphics.Bitmap
import android.media.ExifInterface
import dev.apkcli.core.agent.PreviewPage
import dev.apkcli.core.agent.PreviewScript
import dev.apkcli.core.media.ModelImage
import dev.apkcli.core.model.Block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * The preview the activity has, for the chat's preview tools: the chat's
 * ViewModel outlives the activity, the browser does not. Null while there is
 * no activity, and then the tools say the app is not on screen.
 */
object PreviewBus {
    @Volatile
    var agent: PreviewAgent? = null
}

/**
 * What the agent's preview tools do in the preview: load a page and bring it
 * on screen, look at it, run a script in it. RootScreen makes one for the
 * activity's [PreviewState]; everything runs on the main thread, where the
 * WebView lives and calls back.
 */
class PreviewAgent(
    private val state: PreviewState,
    /** The project the chat works in, whose files the preview serves. */
    private val workspace: () -> File?,
    /** Puts the preview on screen: the workspace, with the preview's pane in front. */
    private val show: () -> Unit,
) {

    suspend fun open(address: String, screenshot: Boolean): PreviewPage = withContext(Dispatchers.Main) {
        val url = PreviewUrls.fromInput(address) ?: return@withContext PreviewPage(url = null)
        state.openProject(workspace())
        show()
        val before = state.settled.value
        state.load(url)
        val ended = withTimeoutOrNull(LOAD_TIMEOUT_MS) { state.settled.first { it > before } } != null
        // A dev server's page goes on loading its modules after the document itself.
        if (ended) delay(SETTLE_MS)
        report(screenshot, loading = !ended || state.progress < 100)
    }

    suspend fun look(screenshot: Boolean): PreviewPage = withContext(Dispatchers.Main) {
        state.openProject(workspace())
        if (state.url == null) {
            show()
            return@withContext PreviewPage(url = null)
        }
        onScreenWithPage()
        report(screenshot, loading = state.progress < 100)
    }

    suspend fun eval(script: String, waitMs: Long, screenshot: Boolean): PreviewScript = withContext(Dispatchers.Main) {
        state.openProject(workspace())
        if (state.url == null) {
            show()
            return@withContext PreviewScript(PreviewPage(url = null), error = "Nothing is open in the preview: open a page with preview_open first.")
        }
        onScreenWithPage()
        val raw = withTimeoutOrNull(EVAL_TIMEOUT_MS) { state.evaluate(wrapped(script)) }
        val answer = raw?.let(::objectOf)
        var value: String? = null
        var error: String? = null
        var pending = false
        val promise = answer?.get("pending")?.let { (it as? JsonPrimitive)?.contentOrNull }
        when {
            answer == null -> error = "the page gave no answer; it may have been loading or going to another page"
            promise != null -> {
                val settled = withTimeoutOrNull(waitMs.coerceAtLeast(POLL_MS)) {
                    var outcome: JsonObject? = null
                    while (outcome == null) {
                        outcome = state.evaluate(outcomeOf(promise))?.let(::objectOf)
                        if (outcome == null) delay(POLL_MS)
                    }
                    outcome
                }
                if (settled == null) pending = true else if (settled.flag("ok")) value = settled.text("value") else error = settled.text("error")
            }
            answer.flag("ok") -> value = answer.text("value")
            else -> {
                val thrown = answer.text("error") ?: "no reason given"
                // A page whose security policy forbids eval: the script runs as
                // it is, which that policy does not stop, and its value comes
                // back as the WebView gives it.
                val direct = if (EVAL_REFUSED.containsMatchIn(thrown)) withTimeoutOrNull(EVAL_TIMEOUT_MS) { state.evaluate(script) } else null
                if (direct != null) value = direct else error = thrown
            }
        }
        // Time for the page to react: a render, a navigation, a request.
        if (promise == null) delay(waitMs)
        PreviewScript(report(screenshot, loading = state.progress < 100, withText = false), value, error, pending)
    }

    /** What the preview shows now, as the tools put it to the model. */
    private suspend fun report(screenshot: Boolean, loading: Boolean, withText: Boolean = true): PreviewPage {
        val read = if (withText) withTimeoutOrNull(EVAL_TIMEOUT_MS) { state.evaluate(TEXT) }?.let(::objectOf) else null
        val (image, problem) = if (screenshot) picture() else null to null
        val (lines, dropped) = state.takeConsole()
        return PreviewPage(
            url = state.currentAddress(),
            title = state.title,
            failure = state.failed?.let { state.failureReason ?: "no answer" },
            httpError = state.httpError,
            loading = loading,
            console = lines,
            consoleDropped = dropped,
            text = read?.text("text").orEmpty(),
            textLength = (read?.get("length") as? JsonPrimitive)?.intOrNull ?: 0,
            screenshot = image,
            screenshotProblem = problem,
        )
    }

    private suspend fun picture(): Pair<Block.Image?, String?> {
        if (!awaitOnScreen()) return null to "No picture: the preview did not come on screen."
        var bitmap = state.capture() ?: return null to "No picture: the preview could not be drawn."
        // Just brought on screen, the page may not have drawn its first frame yet.
        if (uniform(bitmap)) {
            delay(BLANK_RETRY_MS)
            state.capture()?.let { bitmap = it }
        }
        val shown = bitmap
        val image = withContext(Dispatchers.Default) {
            ModelImage.encode(ModelImage.prepared(shown, ExifInterface.ORIENTATION_NORMAL), "preview")
        }
        return image to null
    }

    /**
     * The preview on screen with its page in: a browser made only now, as
     * the pane first comes on screen, starts loading its page then, and one
     * still loading is waited for.
     */
    private suspend fun onScreenWithPage() {
        val before = state.settled.value
        val fresh = !state.hasBrowser()
        show()
        awaitOnScreen()
        if (fresh || state.progress < 100) {
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { state.settled.first { it > before } }
            delay(SETTLE_MS)
        }
    }

    /** Until the browser is laid out on screen, which drawing it needs; false when it never was. */
    private suspend fun awaitOnScreen(): Boolean =
        withTimeoutOrNull(SHOW_TIMEOUT_MS) {
            while (!state.onScreen()) delay(POLL_MS)
            true
        } ?: false

    private fun objectOf(raw: String): JsonObject? = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 800L
        const val SHOW_TIMEOUT_MS = 3_000L
        const val EVAL_TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
        const val BLANK_RETRY_MS = 500L

        /** How a page's Content-Security-Policy refuses eval. */
        val EVAL_REFUSED = Regex("unsafe-eval|EvalError")

        /** The page's visible text: how long it is, and its start. */
        const val TEXT = "(function () { var b = document.body; var t = b ? b.innerText : ''; " +
            "return { length: t.length, text: t.slice(0, 3000) }; })()"

        /**
         * The model's script, given as a JSON string so that nothing in it
         * escapes into this code, run by an indirect eval in the page's global
         * scope. Its last expression's value comes back as JSON, an element as
         * its HTML; a Promise is left to settle into a box read by [outcomeOf].
         */
        const val WRAPPER = "(function () {" +
            " var box = window.__apkcliEval || (window.__apkcliEval = {});" +
            " function shown(v) {" +
            "  if (v === undefined) return 'undefined';" +
            "  if (typeof Element !== 'undefined' && v instanceof Element) return v.outerHTML;" +
            "  try { var s = JSON.stringify(v); return s === undefined ? String(v) : s; } catch (e) { return String(v); }" +
            " }" +
            " function failure(e) { return String((e && e.stack) || e); }" +
            " try {" +
            "  var r = (0, eval)(SOURCE);" +
            "  if (r && typeof r.then === 'function') {" +
            "   var id = String(Date.now()) + String(Math.random()).slice(2);" +
            "   box[id] = null;" +
            "   r.then(function (v) { box[id] = { ok: true, value: shown(v) }; }," +
            "    function (e) { box[id] = { ok: false, error: failure(e) }; });" +
            "   return { pending: id };" +
            "  }" +
            "  return { ok: true, value: shown(r) };" +
            " } catch (e) { return { ok: false, error: failure(e) }; }" +
            "})()"

        fun wrapped(script: String): String = WRAPPER.replace("SOURCE", JsonPrimitive(script).toString())

        /** A settled Promise's outcome from the box, taken out of it; null while it has none. */
        fun outcomeOf(id: String): String {
            val key = JsonPrimitive(id).toString()
            return "(function () { var box = window.__apkcliEval || {}; var r = box[$key];" +
                " if (r) { delete box[$key]; } return r || null; })()"
        }

        /** Every sampled pixel the same: a page not drawn yet, or a blank one. */
        fun uniform(bitmap: Bitmap): Boolean {
            val first = bitmap.getPixel(0, 0)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (bitmap.getPixel(x * (bitmap.width - 1) / 7, y * (bitmap.height - 1) / 7) != first) return false
                }
            }
            return true
        }
    }
}
