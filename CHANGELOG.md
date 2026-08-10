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
• Restoring an identity can now fill your recovery phrase from your password manager, instead of retyping 24 words.
• A peer that doesn't present a valid invite is now turned away instead of landing in your inbox marked "unverified".
• Share links from before invites existed no longer open — ask the sender for a current invite link.
```

### Added

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
