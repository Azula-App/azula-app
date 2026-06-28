package dev.azula.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A little interactive UI azula can render inline in a chat thread.
 * Serializable so it can also travel over the wire inside a [dev.azula.net.Frame.WidgetFrame].
 */
@Serializable
sealed class InlineWidget {
    @Serializable
    @SerialName("decide")
    data class Decide(val q: String, val opts: List<String>) : InlineWidget()

    @Serializable
    @SerialName("poll")
    data class Poll(val q: String, val opts: List<PollOption>) : InlineWidget()

    @Serializable
    @SerialName("dice")
    data class Dice(val label: String = "") : InlineWidget()
}

@Serializable
data class PollOption(val label: String, val votes: Int)
