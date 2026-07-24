package dev.azula.ui

import androidx.compose.runtime.Composable

/**
 * iOS has no app-driven equivalent of Credential Manager's `getCredential`:
 * AutoFill is offered by the system when it recognizes a real password field,
 * not when an app asks. So there's no affordance to render (null) -- the fill
 * path on iOS is [PhraseTextField]'s native `UITextField`, whose
 * `textContentType` is what makes the QuickType bar and third-party providers
 * offer the saved phrase.
 *
 * This is why the return type is nullable rather than always yielding a
 * [PhraseFillResult]: a button here would raise nothing and could only ever
 * report failure.
 */
@Composable
actual fun rememberPlatformPhraseFiller(): (((PhraseFillResult) -> Unit) -> Unit)? = null
