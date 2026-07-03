# Design reference

The UI implements the Claude Design component **`azula.dc.html`**:

https://claude.ai/design/p/e03ecee7-3703-4885-89cc-b94752467881?file=azula.dc.html

## Palette (see `shared/src/dev/azula/theme/Color.kt`)

- Backgrounds: `#070709` / `#0A0A10` / `#0C0C13`, sidebar `#08080D`
- Accent pink: `#FF2D9B`, light `#FF6EC7`, dark `#C4156E`
- Online green: `#52C98A` / `#00FF9C`; warn `#FFD23F`; error `#FF6B6B`

## Fonts

Space Grotesk (UI), JetBrains Mono (code/labels), VT323 + Press Start 2P (retro
widget chrome). Not bundled (OFL, fetched at design time) — `theme/Type.kt` maps
each to the nearest system family. Drop the `.ttf` into
`shared/composeResources/font/` and switch `Type.kt` to `Font(Res.font.…)` to
match the mock exactly.

## Surfaces implemented

Desktop two-pane (sidebar + chat) and mobile single-pane (home → connect → chat);
markdown message bubbles; the thinking indicator; A2UI inline widget surfaces
(the built-in `dice` example; `decide`/`poll` were removed with the demo seeds —
agents render richer surfaces via the bridge's `render_ui`); the terminal view;
and the connect flow (code, QR, holepunch progress animation).
