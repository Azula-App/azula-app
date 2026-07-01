# azula

A peer-to-peer chat app over [iroh](https://iroh.computer) — dark, neon-pink, and
multiplatform. The UI is **Compose Multiplatform** (Android, JVM desktop, iOS)
built from the [Claude Design](https://claude.ai/design) mock `azula.dc.html`.
A companion **Rust CLI** runs on a server and hands an LLM session and a shell
session to connected apps.

## What it does

The app forms three kinds of direct, end-to-end-encrypted iroh connections:

1. **Peer ↔ peer messaging** — two azula apps chat directly (the *zuko*
   conversation). Paste a peer's code to dial them.
2. **LLM session** (*azula*) — the Rust CLI is an **MCP client**: it opens one
   shared session to an MCP server you run and pushes each message in as a
   `tools/call`, streaming the result back as tokens. Lively canned replies in
   the app until that stream is wired in.
3. **Remote shell** (*terminal*) — the Rust CLI bridges a server PTY so the app
   drives the remote session. Local command echo until connected.

Connecting to a code dials all three ALPNs and wires up whichever the remote
serves: a peer app answers `azula/chat/0`; the server answers `azula/llm/0` and
`azula/term/0`.

## Layout

```
shared/        Kotlin Multiplatform library — all UI, state, markdown, protocol
  src/                 common: theme · model · state · ui (Compose) · markdown · net
  src@jvmAndAndroid/   real iroh transport (computer.iroh:iroh)
  src@ios/             Swift-injection bridge → IrohLib
android-app/   Android entry point + EncryptedSharedPreferences key store
jvm-app/       Compose Desktop entry point
ios-app/       SwiftUI entry point + IrohLib-backed transport (iosApp.swift)
azula-cli/     Rust iroh server: MCP-client LLM relay + PTY shell bridge
site/          Cloudflare Worker for azula.app — landing + session-link URLs + deeplink files
docs/          android-iroh.md, ios-iroh.md, deeplinks.md
```

## Web & session links (azula.app)

The `site/` Worker serves the landing page and the URL scheme that links sessions
across web, apps, and LLMs (full design in `site/URLS.md`):

- `https://azula.app/s/<token>` — session invite; opens the app (universal/app
  link) or a web fallback. `<token>` is the session's iroh ticket.
- `https://azula.app/mcp` — *static* MCP endpoint to add to an LLM client once
  (no token in the URL). Pair devices by giving azula a session link — the
  `connect` tool or `azula pair <url>`. The bridge (`azula serve-mcp`) holds
  multiple devices and persists them in `.azula/` + `~/.azula` (Workers can't
  speak iroh, so the bridge runs on a host).
- `/.well-known/apple-app-site-association` + `/.well-known/assetlinks.json` for
  iOS/Android deeplinks (placeholders until the apps are signed — see
  `docs/deeplinks.md`).

Deeplinks route into the app's existing connect flow via `dev.azula.link`.

The transport is an `expect`/`actual` factory (`dev.azula.net.createTransport`).
JVM/Android use the `computer.iroh:iroh` binding directly; iOS delegates to a
Swift implementation injected from `iosApp.swift`. The `-mock` apps
(`android-app-mock` / `jvm-app-mock` / `ios-app-mock`) swap in a `FakeTransport`
(from `mock-support`) to run the real UI over a fake backend for UI tests.

## Wire protocol

Newline-delimited JSON, one `Frame` per line, internally tagged on `"type"`
(shared by the Kotlin client and the Rust server). See
`shared/src/dev/azula/net/Protocol.kt` and `azula-cli/src/proto.rs`. Frames:
`chat`, `input`, `token`, `thinking`, `term`, `widget`.

## Build & run

Uses the Amper toolchain wrapper (`./kotlin`). JDK 21+.

```bash
./kotlin build -m jvm-app        # compile desktop (verified)
./kotlin run   -m jvm-app        # run the desktop app
./kotlin check -m shared -p jvm  # run unit tests (markdown + protocol codec)
./kotlin build -m android-app    # see docs/android-iroh.md (needs NDK-built .so)
./kotlin build -m ios-app        # see docs/ios-iroh.md (add IrohLib in Xcode)
```

The Rust server:

```bash
cd azula-cli && cargo run -- serve   # prints a pairing URL + QR; scan it with the app
# point the LLM channel at an MCP server (stdio child process or remote URL):
cargo run -- serve --mcp-stdio "npx -y @modelcontextprotocol/server-everything"
cargo run -- serve --mcp-url https://example.com/mcp
cargo run -- serve-mcp              # multi-device MCP↔iroh bridge; shows a pairing QR
cargo run -- qr "<code-or-url>"     # render a pairing URL + QR for any code
```

Pairing works both ways: the app shows its code (`azula pair <url>` / the `connect`
tool), **or** the CLI/bridge shows a URL + QR (and the `start_pairing` MCP tool)
that you scan with your phone to pair.

## Status

- **JVM desktop + the Rust CLI build and are verified here.**
- **Android & iOS** ship as complete code; they require their native artifacts
  (NDK-built iroh `.so`; the IrohLib Swift package in Xcode) — see `docs/`.
- Custom fonts (Space Grotesk, JetBrains Mono, VT323, Press Start 2P) fall back
  to system families until the `.ttf` are dropped into
  `shared/composeResources/font/`.
