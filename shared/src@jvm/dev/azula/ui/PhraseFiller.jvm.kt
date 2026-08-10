package dev.azula.ui

import androidx.compose.runtime.Composable

/**
 * Desktop has no system credential store to read a secret back out of, so there
 * is nothing to offer and no affordance to render -- null, not
 * [PhraseFillResult.Unavailable], which would put a button on screen whose only
 * job is to say "no". Typing and pasting keep working exactly as they always
 * have, which is the behavior the spec requires when no store exists.
 */
@Composable
actual fun rememberPlatformPhraseFiller(): (((PhraseFillResult) -> Unit) -> Unit)? = null
