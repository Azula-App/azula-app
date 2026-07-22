package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a save-to-password-manager attempt actually did. All three outcomes stay
 * distinguishable on purpose -- see
 * azula-docs/openspec/specs/onboarding/design.md ("Back-up step"). The recovery
 * phrase is the identity key, so "we didn't store it" and "we stored it
 * somewhere else" must not both render as success.
 */
enum class PhraseSaveResult {
    /** Handed to the platform credential store / share target. */
    SAVED,

    /** The user dismissed the system sheet. Nothing was stored -- and nothing
     *  should be written to the clipboard on their behalf either: they actively
     *  declined to put the phrase anywhere. */
    CANCELLED,

    /** No credential store on this platform, no provider installed, or the save
     *  failed. Callers fall back to the clipboard and must say *copied* rather
     *  than *saved*. */
    UNAVAILABLE,
}

/**
 * Test seam for [rememberPhraseSaver], mirroring [FilePicker]'s. The platform
 * saver is a native modal (the Credential Manager sheet on android, a share
 * sheet on ios) -- a Compose UI test that tapped "save to password manager"
 * would block on a real OS sheet forever. Providing a [PhraseSaver] here swaps
 * it for one the test drives directly; production code provides nothing and
 * gets the platform sheet unchanged.
 */
fun interface PhraseSaver {
    /** Invoked in place of the platform sheet. Call [onResult] exactly once. */
    fun save(phrase: String, onResult: (PhraseSaveResult) -> Unit)
}

/** The active [PhraseSaver] override, or null to use the platform sheet. */
val LocalPhraseSaver = staticCompositionLocalOf<PhraseSaver?> { null }

/**
 * Returns a lambda that hands [phrase] to the platform's password-manager
 * integration, reporting the outcome to its callback.
 *
 * Honors [LocalPhraseSaver] when one is provided (tests); otherwise delegates to
 * [rememberPlatformPhraseSaver].
 */
@Composable
fun rememberPhraseSaver(): (String, (PhraseSaveResult) -> Unit) -> Unit {
    val override = LocalPhraseSaver.current
    // Remembered unconditionally rather than inside the null branch, for the same
    // reason as rememberFilePicker: `remember` calls must not be conditional, and
    // the override is a static composition local so this never actually re-runs.
    val platform = rememberPlatformPhraseSaver()
    return if (override == null) platform else remember(override) {
        { phrase, onResult -> override.save(phrase, onResult) }
    }
}

/**
 * Opens the real platform save flow. Actuals live in src@android (Credential
 * Manager), src@ios (share sheet -- iOS has no public API to add an item to the
 * user's password manager programmatically), and src@jvm (reports
 * [PhraseSaveResult.UNAVAILABLE], so desktop falls back to the clipboard as it
 * always has). Prefer [rememberPhraseSaver] at call sites -- it adds the
 * [LocalPhraseSaver] test seam on top of this.
 */
@Composable
expect fun rememberPlatformPhraseSaver(): (String, (PhraseSaveResult) -> Unit) -> Unit
