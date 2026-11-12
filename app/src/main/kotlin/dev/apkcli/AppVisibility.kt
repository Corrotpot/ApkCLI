package dev.apkcli

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is on screen: between [MainActivity]'s onStart and onStop.
 *
 * Read by the chat to decide whether something the user has to see goes out
 * as a notification (see `AgentService.alert`). onStop also comes when the
 * screen turns off, which is exactly when a sound is wanted. In split screen
 * the app stays started while another window has the focus, and counts as on
 * screen: the user can see it.
 */
object AppVisibility {
    private val _onScreen = MutableStateFlow(false)
    val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    internal fun set(visible: Boolean) {
        _onScreen.value = visible
    }
}
