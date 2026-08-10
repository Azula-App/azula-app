package dev.azula.mock

import dev.azula.a2ui.A2uiAction
import dev.azula.a2ui.A2uiExamples
import dev.azula.model.ConversationKind
import dev.azula.model.MediaKind
import dev.azula.model.Role
import dev.azula.persist.AttachmentDto
import dev.azula.persist.ConversationDto
import dev.azula.persist.MessageDto
import dev.azula.persist.MessageStore
import dev.azula.persist.SurfaceDto
import kotlinx.serialization.json.JsonObject

/**
 * A read-only [MessageStore] backed by a fixed set of fixtures, so the `-mock`
 * apps launch with a populated sidebar (three chats, on top of whatever
 * terminals [FakeTransport.incoming] surfaces) instead of just the one bare
 * auto-surfaced terminal. `PersistenceCoordinator.restoreAll` walks [list] +
 * [load] exactly like it would a real on-disk store, so binding this alone
 * builds both the sidebar rows and each conversation's full message history --
 * no changes needed anywhere in the UI or state layer.
 *
 * [save] and [delete] are no-ops: the mock build persists nothing, so editing
 * a seeded chat (or starting a new one) never survives a restart -- the same
 * "known, but not real persistence" shape as [MockInvitationsStore].
 */
class SeededMessageStore : MessageStore {
    private val fixtures: Map<String, ConversationDto> = mapOf(
        "seed-azula" to azulaThread(),
        "seed-nori" to noriThread(),
        "seed-tavi" to taviThread(),
    )

    override fun list(): List<String> = fixtures.keys.toList()
    override fun load(conversationId: String): ConversationDto? = fixtures[conversationId]
    override fun save(conversationId: String, conversation: ConversationDto) = Unit
    override fun delete(conversationId: String) = Unit
}

// ---------------------------------------------------------------------------
// Fixtures -- message ids are a single sequence (m1..m16) across all three
// conversations, so PersistenceCoordinator.restoreAll's max-trailing-digit scan
// lands on the true highest id no matter which order `list()` iterates.
// ---------------------------------------------------------------------------

/**
 * An LLM-kind thread showing off the two things a real azula reply looks like:
 * markdown (a fenced code block, a bulleted list, inline code) and an A2UI
 * card. The card reuses [A2uiExamples.diceSurface] -- the same builder the
 * live dice demo drives -- rolled once for a non-"?" result, then serialized
 * straight into a [SurfaceDto] the same way `Message.toDto()` does (see
 * `shared/src/dev/azula/persist/MessageMapper.kt`). Restored surfaces are
 * deliberately NOT registered in the live `surfaces` registry (see
 * `PersistenceCoordinator.restoreAll`'s doc), so it renders frozen -- correct
 * here, since there's no live agent behind seeded history to react to a re-roll.
 */
private fun azulaThread(): ConversationDto {
    val dice = A2uiExamples.diceSurface("seed").also { surface ->
        A2uiExamples.handleEvent(
            surface,
            A2uiAction(name = "roll", surfaceId = surface.surfaceId, sourceComponentId = "rollBtn"),
        )
    }
    val diceDto = SurfaceDto(dice.surfaceId, dice.catalogId, JsonObject(dice.components), dice.dataModel)

    val messages = listOf(
        MessageDto(
            id = "m1",
            role = Role.ME,
            text = "hey azula, can you show me how to hash a file and verify it against a manifest?",
        ),
        MessageDto(
            id = "m2",
            role = Role.AZULA,
            text = """
                Sure — here's a small helper that computes a BLAKE3 digest and checks it against an expected hex string:

                ```rust
                fn verify(path: &Path, expected_hex: &str) -> std::io::Result<bool> {
                    let bytes = std::fs::read(path)?;
                    let digest = blake3::hash(&bytes);
                    Ok(digest.to_hex().as_str() == expected_hex)
                }
                ```

                A few things worth knowing:
                - `blake3::hash` reads the whole file into memory — fine for blobs under a few hundred MB, but stream it in chunks for anything bigger.
                - The manifest should store the hex digest, not raw bytes — `to_hex()` gives you a stable, comparable string.
                - Compare the hex strings with `==`, not the raw `Hash` values — hex is easier to log when a check fails.

                Want me to wire this into the media fetch path so a resumed download gets verified automatically?
            """.trimIndent(),
        ),
        MessageDto(
            id = "m3",
            role = Role.ME,
            text = "yes please — and since you're on a roll, show me that dice thing you built too",
        ),
        MessageDto(id = "m4", role = Role.AZULA, text = "sure, here you go 🎲", a2ui = diceDto),
    )
    return ConversationDto(
        name = "Azula",
        kind = ConversationKind.LLM,
        glyph = "✦",
        sub = "assistant",
        preview = "sure, here you go 🎲",
        messages = messages,
    )
}

/**
 * A human peer conversation: a several-turn back-and-forth about getting a
 * direct (non-relayed) connection working -- the kind of day-to-day chatter
 * this app's own dev/test loop actually produces, not lorem ipsum.
 */
private fun noriThread(): ConversationDto {
    val messages = listOf(
        MessageDto(id = "m5", role = Role.THEM, text = "finally got the direct connection working — no relay needed 🎉"),
        MessageDto(id = "m6", role = Role.ME, text = "nice! what was the fix?"),
        MessageDto(
            id = "m7",
            role = Role.THEM,
            text = "turned out my router doesn't do hairpin NAT, so loopback dials never resolved. " +
                "switched to my phone's hotspot and the holepunch went straight through.",
        ),
        MessageDto(id = "m8", role = Role.ME, text = "ha, classic. did the ticket format need to change or was it just the network?"),
        MessageDto(
            id = "m9",
            role = Role.THEM,
            text = "just the network — same ticket, same code. relay fallback kicked in for about 2 seconds before it upgraded to direct",
        ),
        MessageDto(
            id = "m10",
            role = Role.ME,
            text = "love that it upgrades transparently. sending you a build with the resume-on-reconnect fix " +
                "for file transfers — let me know if a killed connection actually resumes now",
        ),
        MessageDto(id = "m11", role = Role.THEM, text = "will do — that one's been biting me every time my laptop sleeps mid-transfer"),
        // The received-audio fixture. Blob-backed like the image, so it takes the
        // modern attachment path rather than LegacyAttachmentView.
        MessageDto(
            id = "m16",
            role = Role.THEM,
            text = "easier to just say it — voice note:",
            attachments = listOf(
                AttachmentDto(
                    id = "att-nori-voice-note",
                    name = "voice-note.wav",
                    mime = "audio/wav",
                    size = TEST_TONE_BYTES.size.toLong(),
                    kind = MediaKind.AUDIO.wire,
                    durationMs = 4_000,
                    blobId = TEST_TONE_BLOB_ID,
                ),
            ),
        ),
    )
    return ConversationDto(
        name = "Nori",
        kind = ConversationKind.PEER,
        glyph = "N",
        sub = "4d · ember · rune",
        preview = "will do — that one's been biting me every time my laptop sleeps mid-transfer",
        peerDescription = "testing holepunch from a coffee shop wifi — flaky, but works",
        messages = messages,
    )
}

/**
 * A second, shorter peer conversation, so the sidebar has some depth beyond one
 * chat -- and the one that carries a **received image**, so the image viewer has
 * something to open.
 *
 * It hangs off the existing "the code text is small on my phone" exchange, which
 * is the one place in the seed where a screenshot is the natural next message.
 * The body is [testCardPng]'s generated test card rather than a committed asset,
 * served from [InMemoryBlobStore] via [TEST_CARD_BLOB_ID] -- see that constant
 * for why this is blob-backed instead of inline, which matters more than it
 * looks.
 */
private fun taviThread(): ConversationDto {
    val messages = listOf(
        MessageDto(id = "m12", role = Role.ME, text = "did the invite QR scan ok on your end?"),
        MessageDto(
            id = "m13",
            role = Role.THEM,
            text = "yep, scanned first try. one thing though — the code text underneath is pretty small on my phone",
        ),
        MessageDto(id = "m14", role = Role.ME, text = "noted, I'll bump the font size a notch"),
        MessageDto(
            id = "m15",
            role = Role.THEM,
            text = "here's the screenshot so you can see how tight it gets",
            attachments = listOf(
                AttachmentDto(
                    id = "att-tavi-screenshot",
                    name = "invite-code.png",
                    mime = "image/png",
                    size = TEST_CARD_BYTES.size.toLong(),
                    kind = MediaKind.IMAGE.wire,
                    width = TEST_CARD_WIDTH,
                    height = TEST_CARD_HEIGHT,
                    blobId = TEST_CARD_BLOB_ID,
                ),
            ),
        ),
    )
    return ConversationDto(
        name = "Tavi",
        kind = ConversationKind.PEER,
        glyph = "T",
        sub = "7q · onyx · wren",
        preview = "here's the screenshot so you can see how tight it gets",
        peerDescription = "running the android build on a pixel 6, minSdk testing",
        messages = messages,
    )
}
