# e2e

Mobile (Android/iOS) end-to-end flows here are Maestro YAML, driven against the
`-mock` apps (`android-app-mock`, `ios-app-mock`) — see `android.yaml`,
`android-back.yaml`, `ios.yaml`, `ios-media.yaml`.

Desktop (JVM) has no native UI-driver equivalent, so its e2e coverage instead
lives as real Compose UI tests: `mock-support/test@jvm/DesktopAppE2eTest.kt`
mounts the actual `DesktopApp` root composable (the same one `jvm-app-mock`
ships) over a `FakeTransport`-backed `AzulaState` via `runComposeUiTest`, and
drives it with real clicks/typing — mirroring the same connect/chat/media
scenarios as the mobile flows above. Run it with:

```
./kotlin test -m mock-support
```
