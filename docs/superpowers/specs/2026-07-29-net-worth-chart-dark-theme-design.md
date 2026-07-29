# Net worth chart: dark theme

## Context

`net-worth-service`'s `ChartService.renderNetWorthChart` (`net-worth-service/src/main/java/com/isym444/networth/service/ChartService.java`) generates the dashboard's history chart as a PNG via a bare `ChartFactory.createTimeSeriesChart(...)` call, with no styling applied afterward — it renders with JFreeChart's default white background, default red/blue/green series colors, and default fonts. The frontend dark-mode pass ([2026-07-29-net-worth-frontend-dark-mode-design.md](2026-07-29-net-worth-frontend-dark-mode-design.md)) could not restyle this image, so it was wrapped in a light `#f4f5f7` panel (`.chart-panel` in `dashboard.component.css`) to avoid the raw white rectangle clashing with the dark page. This spec makes the chart itself dark-native, matching the app's design tokens, and removes the now-unneeded light wrapper.

## Goals

- Recolor the generated PNG to match the app's dark surface tokens (background, gridlines, axis text, title, legend).
- Give each of the three series a distinct, deliberate color: Net worth (hero line) uses the app's emerald accent; Trading212 and Crypto.com get complementary cool/warm colors.
- Clean, modern line rendering: no shape markers, slightly thicker strokes.
- Remove the frontend's light `.chart-panel` wrapper now that the image blends into a dark card natively.

## Non-goals

- No chart library change (stays JFreeChart 1.5.4) and no new dependency.
- No change to chart data, image dimensions (1000×550), output format (PNG), or the `/api/v1/chart` endpoint contract.
- No change to date-axis tick formatting/granularity — that's a data-density concern (currently only test data exists), not a styling one; JFreeChart's default auto-tick behavior is left as-is.
- No theme toggle — this chart has one look, matching the app's dark-only theme.

## Backend changes (`ChartService.java`)

All configuration happens on the `JFreeChart`/`XYPlot` objects already returned by `ChartFactory.createTimeSeriesChart(...)` — no new theme class, no new dependency. Colors are the same hex values as the frontend's CSS tokens, kept as `java.awt.Color` constants in `ChartService`:

| Element | Value | Token equivalent |
|---|---|---|
| Chart background, plot background | `#12161f` | `--surface` |
| Gridlines (both axes) | `#232838` | `--border` |
| Axis line/ticks | `#232838` | `--border` |
| Axis label text | `#8b92a3` | `--text-muted` |
| Chart title text | `#e8eaed` | `--text` |
| Legend background | `#12161f` | `--surface` |
| Legend text | `#8b92a3` | `--text-muted` |
| Net worth series | `#34d399` | `--accent` |
| Trading212 series | `#60a5fa` | (new, chart-only) |
| Crypto.com series | `#fbbf24` | (new, chart-only) |

Rendering details:
- `XYLineAndShapeRenderer`: shapes disabled for all series (`setSeriesShapesVisible(i, false)`), stroke width 2.0f for Trading212/Crypto.com, 2.5f for Net worth (the hero line reads slightly heavier).
- `chart.setBorderVisible(false)` — no outer border, so the PNG's edge is a clean rectangle of the dark background color, sitting flush inside the frontend's card padding.
- Title font: bold, ~16pt, sans-serif logical font (JFreeChart/AWT can't load the app's system-ui stack server-side; `"SansSerif"` is the closest clean equivalent).
- Legend position stays at the bottom (existing `createTimeSeriesChart` default).

## Frontend changes

`dashboard.component.html`: remove the `.chart-panel` wrapper `<div>` — the `<img>` sits directly inside `.chart-card`.

`dashboard.component.css`:
- Delete the `.chart-panel` rule entirely (no more light `#f4f5f7` background needed).
- `.chart-card` keeps its existing `--surface`/`--border`/`--radius`/padding (unchanged from the current dark-mode pass) — the dark PNG now sits directly on that surface with no visible seam.

## Testing

No automated test suite exists for either the backend chart endpoint or the frontend (consistent with prior specs). Verification:
- Backend: `GET /api/v1/chart` returns a 200 PNG; visually inspect the output (save to a file or view via the running frontend) to confirm dark background, correct series colors, readable axis/legend text.
- Frontend: `ng build` succeeds; visual check in a browser that the chart renders flush inside its dark card with no light seam or border mismatch.
