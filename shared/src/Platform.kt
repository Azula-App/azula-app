package dev.azula

/** Platform display name, surfaced as a small tag under the brand. */
expect fun platformName(): String

/** Current wall-clock time in unix seconds — used for invite issued/expires
 *  timestamps (see azula-docs/docs/invitations.md's payload layout). */
expect fun epochSeconds(): Long
