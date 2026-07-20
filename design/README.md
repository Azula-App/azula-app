# Design reference

The UI implements the Claude Design component **`azula.dc.html`**:

https://claude.ai/design/p/e03ecee7-3703-4885-89cc-b94752467881?file=azula.dc.html

## Palette and tokens

**Normative source: [`azula-docs/openspec/specs/design-system/design.md`](../../azula-docs/openspec/specs/design-system/design.md).**
Don't read tokens out of this file — it used to carry its own copy of the palette
and drifted. `theme/src/dev/azula/theme/Color.kt` (`AzulaColors`) and
`a2ui/src/dev/azula/a2ui/A2uiTokens.kt` are the app-side derived copies; §10 of
the design system maps their current names to canonical ones.

## Fonts

- **JetBrains Mono** (code, labels, metrics, the wordmark) — **bundled**, at
  `theme/composeResources/font/jetbrainsmono_{regular,bold}.ttf`, loaded via
  `Fonts.mono` in `theme/Type.kt`.
- **Space Grotesk** (UI prose) — **not bundled**; `Fonts.ui` currently resolves to
  the system sans on every platform. Drop the `.ttf` into
  `theme/composeResources/font/` and switch `Type.kt` to `Font(Res.font.…)` to
  match the mock exactly.
- **VT323 / Press Start 2P** — declared in `Type.kt` but mapped to
  `FontFamily.Monospace` and unused. They are not part of the design system;
  either bundle them for a named purpose or delete them.

## Surfaces implemented

Desktop two-pane (sidebar + chat) and mobile single-pane (home → connect → chat);
markdown message bubbles; the thinking indicator; A2UI inline widget surfaces
(the built-in `dice` example; `decide`/`poll` were removed with the demo seeds —
agents render richer surfaces via the bridge's `render_ui`); the terminal view;
and the connect flow (code, QR, holepunch progress animation).
