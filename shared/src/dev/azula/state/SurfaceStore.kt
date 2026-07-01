package dev.azula.state

import androidx.compose.runtime.mutableStateMapOf
import dev.azula.a2ui.A2uiSurface
import dev.azula.core.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Holds every live A2UI surface, keyed by surfaceId. Shared state (like
 * [ConversationStore]) that the chat, a2ui, and connect flows all read and write;
 * carved out of AzulaState so those feature services can build on one registry.
 * Restored-from-disk surfaces are deliberately NOT registered here so they render
 * frozen (see the renderer's `frozen` flag).
 */
@Inject
@SingleIn(AppScope::class)
class SurfaceStore {
    val surfaces = mutableStateMapOf<String, A2uiSurface>()
}
