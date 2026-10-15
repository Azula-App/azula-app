# Changelog

<!--
  ONLY WHAT A USER CAN OBSERVE. An entry earns its place if a user could notice
  its absence. Dependency bumps, refactors, DI rewiring, module moves, tests, CI,
  and spec/doc changes do not belong here in any form.

  Two tiers per version, both written in the SAME COMMIT as the change:

    ### Store notes   one fenced block, sent to Google Play, TestFlight, and the
                      App Store VERBATIM -- no rendering, no stripping, no
                      truncation. Type the bullet you want to ship. Must stay
                      under 500 bytes, which is Google Play's limit and the
                      smallest of the three. Re-word it as entries accumulate.

    ### Added / Changed / Deprecated / Removed / Fixed / Security
                      the full record, with as much detail as an entry deserves.
                      Not size-constrained -- the store block is the summary, so
                      the pressure to be brief lands there, not here.

  Adding an entry means revising the store block too, so it still describes the
  release as it now stands. Cutting a release fails if either tier is empty.

  Sections are promoted from [Unreleased] to [X.Y.Z] by .github/scripts/
  promote_changelog.sh when release.yml cuts a tag; do not rename them by hand.

  Versions before 0.0.8 were tagged before this file existed. Their entries were
  reconstructed from the commit range and are best-effort, not a contemporaneous
  record.
-->

## [Unreleased]

### Store notes

```text
• Restoring an identity can now fill your recovery phrase from your password manager.
• Peers without a valid invite are now turned away instead of landing in your inbox as "unverified".
• Share links from before invites no longer open — ask the sender for a current invite.
• Agent sessions now reconnect on their own when the link drops or you return to them.
• On iPhone and iPad, reopening azula now syncs your linked devices right away.
• You can now select and copy message text.
```

### Added

- Message text in a conversation can now be selected and copied. Press and hold
  a word in any chat bubble (yours, a peer's, or an agent's) to select it, drag
  the handles to extend the selection, and use the system toolbar to copy it; on
  desktop, drag with the mouse. Selection works across the paragraphs, lists,
  and code blocks of a single message and includes an attachment's caption, but
  does not cross from one message into the next. Tapping an attachment, or
  scrolling the conversation, behaves as before.

- The restore step can fill your 24-word recovery phrase from the device's
  password manager instead of making you retype it. On Android it asks
  Credential Manager; on iOS the phrase field is a real native one, so AutoFill
  offers a saved phrase the way it does anywhere else. If nothing is saved, the
  affordance tells you so rather than doing nothing, and typing or pasting the
  phrase still works exactly as before.

### Changed

- Every connection request in the inbox is now invite-verified. A peer that
  presents no invite, or one that doesn't verify, is dropped at the connection
  rather than held for review — the "unverified" state a request could be in is
  gone, along with the setting that allowed it.

### Removed

- The pre-invite share links (`azula.app/s/…`, `azula.app/connect/…`, and
  `azula://connect?code=…`) are no longer recognized. Pasting or opening one now
  does nothing rather than starting a connection; current invite links
  (`azula.app/i/…`) are unaffected.
- The QR beside your raw ticket now encodes the ticket itself rather than a
  link, since the page that link pointed at is gone. The invite QR above it is
  unchanged and is still the one to share.

### Fixed

- A conversation with a CLI agent session (`azula mcp`, such as Claude Code or
  OpenClaw) no longer stays offline after its connection drops or the session
  restarts. The app now dials the session back itself: when the connection
  drops, when you open the conversation, when the app's network endpoint
  restarts, and when the app returns to the foreground. Before, only
  the session could reconnect, and it often couldn't reach a phone whose app was
  in the background. The session has to present a currently valid certificate
  on every reconnect, so a session whose certificate has expired, or whose
  computer you've removed, is not brought back. Archived sessions aren't redialed
  either. An `azula mcp` started without `--session` gets a new conversation on
  each run, so only a named session's conversation can come back after a
  restart. Terminal sessions still reconnect from the computer's side.
- On iPhone and iPad, returning to azula now syncs with your other linked
  devices and reconnects offline agent sessions right away. iOS never told the
  app it had been in the background, so neither happened on return: linked
  devices waited for the next cold launch, and a session for you to open its
  conversation. Android already did both.
- On iPhone and iPad, azula now knows when it is in the background, which is
  when it raises notifications. Before, it always counted as in the foreground,
  so iOS never showed a notification for a message or a connection request at
  all. The background notifications added in 0.0.7 only ever worked on Android.
  They are still rare on iOS: iOS suspends azula almost as soon as you leave it,
  a suspended app receives nothing, and whatever was sent in the meantime
  arrives when you come back, without a notification. Only something that
  arrives in the moment before azula is suspended can notify, and the first time
  that happens iOS asks for notification permission, possibly over whichever
  app you switched to.

## [0.0.7] - 2026-07-26

### Store notes

```text
• Sessions from a device you haven't accepted yet are now held for approval before they can chat, drive a surface, or open a terminal.
• Conversations are grouped by session, and sessions past their expiry are archived instead of staying active.
• Messages that arrive while azula is in the background now raise a notification, including ones delivered through a relay.
```

### Added

- Sessions from a device you have not accepted are held for admission before
  they can send chat, drive an A2UI surface, or open a terminal session.
- Notifications now fire for messages that arrived while the app was in the
  background, including messages delivered through a relay rather than directly.

### Changed

- Conversations are grouped by session rather than by device, and a session past
  its expiry is archived rather than staying active.
