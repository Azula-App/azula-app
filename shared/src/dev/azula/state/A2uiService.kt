package dev.azula.state

import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiCodec
import dev.azula.a2ui.A2uiExamples
import dev.azula.a2ui.A2uiSurface
import dev.azula.core.AppScope
import dev.azula.net.Frame
import dev.azula.net.FrameCodec
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Handles user interactions with A2UI surfaces — button actions and two-way input
 * changes. When a live stream is open the action is encoded and sent to the peer;
 * otherwise it's interpreted locally against the surface. Carved out of AzulaState.
 */
@Inject
@SingleIn(AppScope::class)
class A2uiService(
    private val convStore: ConversationStore,
    private val surfaceStore: SurfaceStore,
    private val persistence: PersistenceCoordinator,
    private val scope: CoroutineScope,
) {
    fun onAction(convId: String, action: A2uiAction, isDemo: Boolean) {
        val stream = convStore.conv(convId).stream
        if (stream != null) {
            scope.launch {
                runCatching { stream.sendLine(FrameCodec.encode(Frame.A2uiAction(A2uiCodec.encodeAction(action)))) }
            }
        } else {
            surfaceStore.surfaces[action.surfaceId]?.let { A2uiExamples.handleEvent(it, action) }
            persistence.save(convId, isDemo)
        }
    }

    fun onDataChange(convId: String, surface: A2uiSurface, path: String, value: JsonElement, isDemo: Boolean) {
        surface.setData(path, value)
        persistence.save(convId, isDemo)
    }
}
