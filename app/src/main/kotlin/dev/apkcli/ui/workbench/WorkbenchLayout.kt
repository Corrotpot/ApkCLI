package dev.apkcli.ui.workbench

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How the two panes share the screen when both are shown. */
enum class SplitOrientation {
    /** Side by side when there is room for it, otherwise stacked. */
    AUTO,
    SIDE_BY_SIDE,
    STACKED,
}

/**
 * The workspace's tabs: the agent, the project's files, its terminals. Split,
 * the agent keeps one side and the other shows files or terminals.
 */
enum class Pane { FILES, CHAT, TERMINAL }

/**
 * Everything about the workbench arrangement the user can change.
 *
 * Fractions are stored per orientation, because a chat width that is right
 * side by side is wrong stacked — rotating the device would otherwise carry a
 * 30% width over into a 30% height.
 */
@Immutable
data class WorkbenchLayout(
    /**
     * Both panes at once. Null until the user decides, and then resolved by
     * size: split on a tablet, a foldable or in landscape, single pane on a
     * phone held upright, where two stacked panes are each too short to use.
     */
    val split: Boolean? = null,
    val orientation: SplitOrientation = SplitOrientation.AUTO,
    /** Chat after the files: to their right, or below them. Where Kiro and VS Code put it. */
    val chatLast: Boolean = true,
    /** Chat's share of the width, side by side. */
    val sideChatFraction: Float = DEFAULT_SIDE_CHAT,
    /** Chat's share of the height, stacked. */
    val stackChatFraction: Float = DEFAULT_STACK_CHAT,
    /** The explorer beside the editor, when the files pane is wide enough for both. */
    val explorerVisible: Boolean = true,
    /** The explorer's share of the files pane. */
    val explorerFraction: Float = DEFAULT_EXPLORER,
    /** The pane shown when not split. */
    val focus: Pane = Pane.CHAT,
    /** Split: what the side beside the agent shows, files or terminals. */
    val side: Pane = Pane.FILES,
) {
    fun splitFor(roomy: Boolean): Boolean = split ?: roomy

    /** The arrangement as it applies to a [width] x [height] area. */
    @Immutable
    data class Resolved(val sideBySide: Boolean, val split: Boolean, val canSplit: Boolean)

    fun resolve(width: Dp, height: Dp): Resolved {
        val sideBySide = sideBySideFor(width.value, height.value)
        val canSplit = if (sideBySide) {
            width >= MIN_PANE_WIDTH * 2 + 8.dp
        } else {
            height >= MIN_PANE_HEIGHT * 2 + 8.dp
        }
        // An undecided split defaults on only where it is comfortable.
        val roomy = width.value >= WIDE_DP || width > height
        return Resolved(sideBySide, splitFor(roomy) && canSplit, canSplit)
    }

    fun sideBySideFor(widthDp: Float, heightDp: Float): Boolean = when (orientation) {
        SplitOrientation.SIDE_BY_SIDE -> true
        SplitOrientation.STACKED -> false
        SplitOrientation.AUTO -> widthDp >= WIDE_DP || widthDp > heightDp
    }

    fun chatFraction(sideBySide: Boolean): Float = if (sideBySide) sideChatFraction else stackChatFraction

    fun withChatFraction(sideBySide: Boolean, fraction: Float): WorkbenchLayout {
        val clamped = fraction.coerceIn(MIN_FRACTION, MAX_FRACTION)
        return if (sideBySide) copy(sideChatFraction = clamped) else copy(stackChatFraction = clamped)
    }

    companion object {
        const val DEFAULT_SIDE_CHAT = 0.42f
        const val DEFAULT_STACK_CHAT = 0.55f
        const val DEFAULT_EXPLORER = 0.34f
        const val MIN_FRACTION = 0.15f
        const val MAX_FRACTION = 0.85f

        /** One step of "wider chat" / "narrower chat" in the layout menu. */
        const val STEP = 0.1f

        /** Width from which AUTO always means side by side. */
        const val WIDE_DP = 600f

        /** Smallest usable pane, per axis. Below this a drag collapses the pane. */
        val MIN_PANE_WIDTH: Dp = 220.dp
        val MIN_PANE_HEIGHT: Dp = 150.dp
    }
}

/**
 * Persistence for [WorkbenchLayout].
 *
 * Plain SharedPreferences, in the same small file as the language choice: the
 * values are a handful of scalars, read once at start, and the language one has
 * to be readable synchronously from `attachBaseContext` anyway.
 */
private class WorkbenchPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("apkcli_ui", Context.MODE_PRIVATE)

    fun load(): WorkbenchLayout {
        val defaults = WorkbenchLayout()
        return WorkbenchLayout(
            split = if (prefs.contains(KEY_SPLIT)) prefs.getBoolean(KEY_SPLIT, true) else null,
            orientation = prefs.getString(KEY_ORIENTATION, null)
                ?.let { name -> SplitOrientation.entries.firstOrNull { it.name == name } }
                ?: defaults.orientation,
            chatLast = prefs.getBoolean(KEY_CHAT_LAST, defaults.chatLast),
            sideChatFraction = prefs.getFloat(KEY_SIDE, defaults.sideChatFraction).sane(defaults.sideChatFraction),
            stackChatFraction = prefs.getFloat(KEY_STACK, defaults.stackChatFraction).sane(defaults.stackChatFraction),
            explorerVisible = prefs.getBoolean(KEY_EXPLORER_VISIBLE, defaults.explorerVisible),
            explorerFraction = prefs.getFloat(KEY_EXPLORER, defaults.explorerFraction).sane(defaults.explorerFraction),
            focus = prefs.getString(KEY_FOCUS, null)
                ?.let { name -> Pane.entries.firstOrNull { it.name == name } }
                ?: defaults.focus,
            side = prefs.getString(KEY_SIDE_PANE, null)
                ?.let { name -> Pane.entries.firstOrNull { it.name == name && it != Pane.CHAT } }
                ?: defaults.side,
        )
    }

    fun save(layout: WorkbenchLayout) {
        prefs.edit().apply {
            if (layout.split == null) remove(KEY_SPLIT) else putBoolean(KEY_SPLIT, layout.split)
            putString(KEY_ORIENTATION, layout.orientation.name)
            putBoolean(KEY_CHAT_LAST, layout.chatLast)
            putFloat(KEY_SIDE, layout.sideChatFraction)
            putFloat(KEY_STACK, layout.stackChatFraction)
            putBoolean(KEY_EXPLORER_VISIBLE, layout.explorerVisible)
            putFloat(KEY_EXPLORER, layout.explorerFraction)
            putString(KEY_FOCUS, layout.focus.name)
            putString(KEY_SIDE_PANE, layout.side.name)
        }.apply()
    }

    /** A corrupt or hand-edited value must not produce an unusable layout. */
    private fun Float.sane(fallback: Float): Float =
        if (isNaN() || this < WorkbenchLayout.MIN_FRACTION || this > WorkbenchLayout.MAX_FRACTION) fallback else this

    private companion object {
        const val KEY_SPLIT = "layout.split"
        const val KEY_ORIENTATION = "layout.orientation"
        const val KEY_CHAT_LAST = "layout.chatLast"
        const val KEY_SIDE = "layout.sideChatFraction"
        const val KEY_STACK = "layout.stackChatFraction"
        const val KEY_EXPLORER_VISIBLE = "layout.explorerVisible"
        const val KEY_EXPLORER = "layout.explorerFraction"
        const val KEY_FOCUS = "layout.focus"
        const val KEY_SIDE_PANE = "layout.side"
    }
}

/**
 * Owns the layout for the whole Activity.
 *
 * A ViewModel rather than composition state because two screens change it —
 * the workbench while dragging, and Settings — and because a rotation must not
 * lose a split the user just adjusted.
 */
class WorkbenchViewModel(context: Context) : ViewModel() {

    private val prefs = WorkbenchPrefs(context.applicationContext)

    private val _layout = MutableStateFlow(prefs.load())
    val layout: StateFlow<WorkbenchLayout> = _layout.asStateFlow()

    fun update(transform: (WorkbenchLayout) -> WorkbenchLayout) {
        _layout.update(transform)
        prefs.save(_layout.value)
    }

    /** Back to the shipped arrangement, keeping only whether the user wanted a split. */
    fun reset() = update { WorkbenchLayout(split = it.split, focus = it.focus) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WorkbenchViewModel(context.applicationContext) as T
            }
    }
}
