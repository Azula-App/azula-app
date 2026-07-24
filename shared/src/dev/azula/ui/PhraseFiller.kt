package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a fill-from-password-manager attempt actually did -- the read-side
 * counterpart of [PhraseSaveResult]. See
 * azula-docs/openspec/specs/onboarding/design.md ("Restore step").
 *
 * [Cancelled] and [Unavailable] stay distinguishable for the same reason the
 * save side keeps them apart: "you dismissed the sheet" and "your vault has
 * nothing for this app" are different facts, and collapsing them would make an
 * empty vault look like a bug (or vice versa).
 */
sealed interface PhraseFillResult {
    /** The user picked a credential. [phrase] is its stored password, unvalidated. */
    data class Filled(val phrase: String) : PhraseFillResult

    /** The user dismissed the provider's sheet. The input must be left untouched. */
    data object Cancelled : PhraseFillResult

    /** No credential for this app, no provider installed, or the request failed. */
    data object Unavailable : PhraseFillResult
}

/**
 * Test seam for [rememberPhraseFiller], mirroring [PhraseSaver]'s. The platform
 * filler raises the provider's own sheet -- a Compose UI test that tapped "fill
 * from password manager" would block on a real OS sheet forever. Providing a
 * [PhraseFiller] here swaps it for one the test drives directly; production code
 * provides nothing and gets the platform sheet unchanged.
 */
fun interface PhraseFiller {
    /** Invoked in place of the platform sheet. Call [onResult] exactly once. */
    fun fill(onResult: (PhraseFillResult) -> Unit)
}

/** The active [PhraseFiller] override, or null to use the platform sheet. */
val LocalPhraseFiller = staticCompositionLocalOf<PhraseFiller?> { null }

/**
 * Returns a lambda that asks the platform's credential store for a saved
 * recovery phrase, or **null** where the platform has no explicit fill flow.
 *
 * Null is not the same as [PhraseFillResult.Unavailable]: null means "this
 * platform has no affordance to offer" (iOS fills through the native field's
 * AutoFill instead; desktop has no credential store at all), so the caller
 * renders no button. Unavailable means the button exists, was pressed, and the
 * provider had nothing -- which the caller reports.
 *
 * Honors [LocalPhraseFiller] when one is provided (tests); otherwise delegates
 * to [rememberPlatformPhraseFiller].
 */
@Composable
fun rememberPhraseFiller(): (((PhraseFillResult) -> Unit) -> Unit)? {
    val override = LocalPhraseFiller.current
    // Remembered unconditionally rather than inside the null branch, for the same
    // reason as rememberPhraseSaver: `remember` calls must not be conditional, and
    // the override is a static composition local so this never actually re-runs.
    val platform = rememberPlatformPhraseFiller()
    return if (override == null) platform else remember(override) {
        { onResult -> override.fill(onResult) }
    }
}

/**
 * Opens the real platform fill flow, or returns null where there isn't one.
 * Actuals live in src@android (Credential Manager's `getCredential`), src@ios
 * (null -- iOS offers the phrase through the native `UITextField`'s AutoFill in
 * [PhraseTextField], not through an app-driven request), and src@jvm (null --
 * no system credential store, so paste keeps being the way in). Prefer
 * [rememberPhraseFiller] at call sites -- it adds the [LocalPhraseFiller] test
 * seam on top of this.
 */
@Composable
expect fun rememberPlatformPhraseFiller(): (((PhraseFillResult) -> Unit) -> Unit)?
