package org.trackertv.player.ui.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Color of the focused poster; the app background leans toward it. Null when nothing colorful has focus. */
val LocalAmbient = staticCompositionLocalOf<MutableState<Color?>> { mutableStateOf(null) }
