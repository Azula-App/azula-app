# e2e

Mobile (Android/iOS) end-to-end flows here are Maestro YAML, driven against the
`-mock` apps (`android-app-mock`, `ios-app-mock`) — see `android.yaml`,
`android-back.yaml`, `ios.yaml`, `ios-media.yaml`.

The `-mock` apps launch **pre-populated** (`mock-support`'s `SeededMessageStore` +
`FakeTransport`): three seeded chats (Azula/Nori/Tavi) and two terminals —
`mockterm` (`mo · onyx · ivy`, an interactive echo shell) and `buildbot`
(`bu · kite · lyra`, a canned transcript). Row labels for the terminals come from
`ConversationStore.mnemonicCode`, so changing a demo peer's id renames its row and
these selectors with it. Keep these flows thin — packaging and platform glue only
(see `azula-docs/docs/testing.md`); behavioral depth belongs in the JVM Compose
suite.

## Layout: one entry flow per platform, shared journeys in `subflows/`

The four top-level flows are **thin platform shells**. The journeys they share live
in `subflows/` (`home.yaml`, `terminal.yaml`, `connect-peer.yaml`), pulled in with
`runFlow`. Maestro (2.5.1) requires a config header in *every* flow file and a
subflow does **not** inherit the caller's `appId` — it fails validation outright
without one — so each subflow declares `appId: ${APP_ID}` and every caller injects
`APP_ID` (plus `PLATFORM`, which names the screenshots) through `runFlow`'s `env`.
That indirection is the price of running one journey against two app ids; it is not
decoration, and dropping it breaks the flows at parse time, before any step runs.

Directory-mode `maestro test e2e/` does not descend into subdirectories at all, so
`subflows/` is never picked up and run standalone (which is just as well — a bare
`${APP_ID}` has nothing to resolve against).

The split is by **platform, not form factor**, because what differs is platform glue:
`appId` (`app.azula.mock` vs `ios-app-mock`), the hardware Back key (Android only —
`android-back.yaml`), and the out-of-process PHPicker (iOS only — `ios-media.yaml`).
Everything else is one journey, written once.

Don't add a "tablet/desktop" Maestro flow. The app picks its layout by width
(`shared/src/dev/azula/App.kt`: `DesktopApp` at ≥820dp, else `MobileApp`), so a
tablet would render the desktop layout — which `DesktopAppE2eTest` already drives
in-process, faster and without a device. A tablet flow would only re-test shared
composables at emulator cost, which is exactly what `testing.md` says not to do.

Desktop (JVM) has no native UI-driver equivalent, so its e2e coverage instead
lives as real Compose UI tests: `mock-support/test@jvm/DesktopAppE2eTest.kt`
mounts the actual `DesktopApp` root composable (the same one `jvm-app-mock`
ships) over a `FakeTransport`-backed `AzulaState` via `runComposeUiTest`, and
drives it with real clicks/typing — mirroring the same connect/chat/media
scenarios as the mobile flows above. Run it with:

```
./kotlin test -m mock-support
```
