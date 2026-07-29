# Net worth dashboard: dark mode + visual polish

## Context

`net-worth-frontend` (see [2026-07-29-net-worth-frontend-design.md](2026-07-29-net-worth-frontend-design.md)) is functionally complete — access gate, dashboard with headline stats, chart, and history table — but is styled with only default browser chrome (system font, no color system, bare `<table>`/`<input>`/`<button>` styling). This spec covers the deferred visual-design pass: a single polished dark theme applied across both components.

## Goals

- Replace the unstyled UI with a cohesive dark, "modern fintech dashboard" visual design (dark slate surfaces, one emerald accent, card-based layout).
- Apply consistent design tokens (CSS custom properties) so both components draw from the same palette/spacing scale.
- Improve perceived polish: card grouping, spacing rhythm, hover/focus states, loading/error/empty states no longer bare text.

## Non-goals

- No light theme / no theme toggle — dark is the only theme (confirmed with user).
- No changes to component logic, signals, HTTP calls, or the `AuthService`/`AuthInterceptor` — this is CSS/template markup only.
- No changes to the backend chart rendering — the chart PNG keeps its own (light) background; the frontend just frames it in a card rather than trying to restyle pixels it doesn't control.
- No new UI framework/component library — plain CSS with custom properties, consistent with the existing minimal-dependency approach.

## Design tokens

Defined once as CSS custom properties on `:root` in `styles.css`, consumed by both components:

| Token | Value | Use |
|---|---|---|
| `--bg` | `#0b0e14` | page background |
| `--surface` | `#12161f` | card backgrounds |
| `--surface-raised` | `#181d29` | nested/hover surfaces |
| `--border` | `#232838` | hairline borders |
| `--text` | `#e8eaed` | primary text |
| `--text-muted` | `#8b92a3` | secondary text, labels |
| `--accent` | `#34d399` | primary actions, positive figures, focus rings |
| `--accent-hover` | `#2bbf87` | button hover |
| `--error` | `#f87171` | error text |
| `--error-bg` | `#2a1414` | error banner background |
| `--radius` | `10px` | card/button/input corner radius |

Font stack stays the existing system-ui sans stack — no new font loading.

## Component changes

### Global (`styles.css`)

- `:root` custom properties above.
- `body` background `--bg`, text `--text`, base font-size/line-height reset.

### Access gate (`access-gate.component.*`)

- `.gate` becomes a `--surface` card: border `1px solid --border`, `--radius`, padding, subtle box-shadow, vertically centered on the dark `--bg` page (via `:host` flex centering) rather than just top-margin.
- Heading/paragraph recolored to `--text` / `--text-muted`.
- Input: dark surface background, `--border` border, `--radius`, `--accent` focus ring (`outline`/`box-shadow` on `:focus`), placeholder in `--text-muted`.
- Button: filled `--accent` background, dark text for contrast, hover → `--accent-hover`, disabled → reduced opacity + `not-allowed` cursor (kept from current behavior).

### Dashboard (`dashboard.component.*`)

- **Header**: flex row kept, heading recolored, "Take snapshot" button restyled to match the gate's accent button (pill-shaped, same hover/disabled treatment); disabled+in-flight state gets a small inline spinner (CSS-only, `border`-based) next to the label instead of text-only swap.
- **Headline stats**: `.headline` becomes a 3-column grid of cards (`--surface`, `--border`, `--radius`, padding). Label (`span`) in `--text-muted` uppercase small-caps-style (`text-transform: uppercase; letter-spacing`), value (`strong`) large and bold. The "Net worth" card gets a visually distinct treatment — `--accent` border and slightly larger value — since it's the headline number.
- **Chart**: wrapped in a `--surface` card with padding and `--radius`; since the PNG itself has a light background, an inner white/near-white rounded panel (`background: #f4f5f7`) sits behind the `<img>` so it reads as a deliberate "chart panel" rather than a raw image floating on dark.
- **Table**: wrapped in a `--surface` card; header row `--text-muted` uppercase small text on `--surface-raised`; body rows use `--border` for the hairline divider (swapped from `#ddd`); row hover → `--surface-raised` background; numeric columns get `font-variant-numeric: tabular-nums` for alignment.
- **Loading / empty / error states**: loading text and "no snapshots yet" text recolored to `--text-muted` with a bit of vertical padding so they don't look like a rendering glitch; `.error` becomes a banner (`--error-bg` background, `--error` text, `--radius`, padding, left accent border) instead of bare bold red text.

## Testing

No automated tests exist for this frontend (consistent with the original spec's non-goals) and this change touches only CSS/template markup, not logic — verified manually via `ng serve`, checked in a browser against: access gate (empty/filled/disabled button states), dashboard with data, loading state, error state (e.g. wrong key → 401), and empty history state.
