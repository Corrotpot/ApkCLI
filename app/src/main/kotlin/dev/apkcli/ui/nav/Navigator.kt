package dev.apkcli.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Where the app can be: the main menu, the project list, a project's
 * workspace, and settings — the design's four screens.
 *
 * Deliberately a small sealed hierarchy with no library behind it: a `when`
 * expresses four screens, and a navigation framework would add a dependency,
 * an argument serialization contract and a graph DSL for nothing more.
 *
 * The workspace's tabs (agent, files, terminal) are not destinations. They
 * change what the workspace shows, not where the app is, and modelling them
 * as destinations would make "back" step through tab switches.
 */
sealed interface Destination {

    /** Stable id for state restoration. Never shown, never translated. */
    val key: String

    /** The main menu: open, create or clone a project; recent ones. */
    data object Home : Destination {
        override val key = "home"
    }

    /** Every project on the device, searchable, with their actions. */
    data object Projects : Destination {
        override val key = "projects"
    }

    /** One project: the agent, its files and its terminals. */
    data object Workspace : Destination {
        override val key = "workspace"
    }

    data object Settings : Destination {
        override val key = "settings"
    }

    companion object {
        val all = listOf(Home, Projects, Workspace, Settings)
    }
}

/**
 * Back stack.
 *
 * Survives configuration change and process death through [rememberSaveable]:
 * the Activity does not restart on rotation, but it is destroyed when the
 * process is killed in the background, and coming back to the main menu
 * from the middle of a project is a papercut worth avoiding.
 */
class Navigator internal constructor(
    private val stack: SnapshotStateList<Destination>,
) {
    val current: Destination get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun push(destination: Destination) {
        if (stack.lastOrNull() != destination) stack.add(destination)
    }

    /**
     * Go to [destination], dropping anything above an earlier visit to it:
     * the main menu reached from a project is the same main menu, not a
     * second one on top.
     */
    fun navigate(destination: Destination) {
        val index = stack.indexOf(destination)
        if (index >= 0) {
            while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(destination)
        }
    }

    fun pop(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceAll(destination: Destination) {
        stack.clear()
        stack.add(destination)
    }
}

@Composable
fun rememberNavigator(start: Destination = Destination.Home): Navigator {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<Destination>, String>(
            save = { list -> list.map { it.key } },
            restore = { keys ->
                val restored = keys.mapNotNull { key -> Destination.all.firstOrNull { it.key == key } }
                mutableStateListOf<Destination>().apply { addAll(restored.ifEmpty { listOf(start) }) }
            },
        ),
    ) {
        mutableStateListOf(start)
    }
    return remember { Navigator(stack) }
}
