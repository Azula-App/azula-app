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
```

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
