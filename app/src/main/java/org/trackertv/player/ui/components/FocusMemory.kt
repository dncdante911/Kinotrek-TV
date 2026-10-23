package org.trackertv.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Remembers which element of a screen had focus, so coming back from the player or another
 * screen puts the user exactly where they left instead of at the top.
 */
class FocusMemory {
    var key: String? = null
        private set
    private val requesters = HashMap<String, FocusRequester>()

    internal fun register(key: String, requester: FocusRequester) { requesters[key] = requester }
    internal fun unregister(key: String, requester: FocusRequester) { if (requesters[key] === requester) requesters.remove(key) }
    internal fun focused(key: String) { this.key = key }

    fun restore(): Boolean {
        val requester = key?.let(requesters::get) ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }

    fun forget() { key = null }
}

@Composable
fun Modifier.remembered(memory: FocusMemory, key: String): Modifier {
    val requester = remember { FocusRequester() }
    DisposableEffect(memory, key) {
        memory.register(key, requester)
        onDispose { memory.unregister(key, requester) }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) memory.focused(key) }
}

/** On entering a screen: restore the remembered element, or focus [fallback]. */
@Composable
fun InitialFocus(memory: FocusMemory, fallback: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!memory.restore()) runCatching { fallback.requestFocus() }
    }
}
