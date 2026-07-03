package dev.azula

/** Platform display name, surfaced as a small tag under the brand. */
expect fun platformName(): String

/** Whether this platform has an on-screen (soft) keyboard. False on desktop
 *  (jvm) -- a live terminal there is captured via the hardware keyboard (see
 *  RawTerminalInput.jvm.kt), so terminal-only chrome that's tied to the soft
 *  keyboard's visibility elsewhere (mobile) should stay always-on there. */
expect val platformHasSoftKeyboard: Boolean

/** Current wall-clock time in unix seconds — used for invite issued/expires
 *  timestamps (see azula-docs/docs/invitations.md's payload layout). */
expect fun epochSeconds(): Long
