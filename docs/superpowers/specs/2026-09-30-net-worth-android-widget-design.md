# Net Worth Android Widget — Design

Date: 2026-09-30

## Goal

An Android home-screen widget (Jetpack Glance) showing the current net worth and time-to-milestone
projections from the existing `net-worth-service` API. It is modelled on the Countdown Android widget
(`countdown/android`) and is intended for the user's Oppo tablet.

## Decisions (agreed)

| Topic | Decision |
|---|---|
| Content | Headline net worth + milestones/projections (not a per-source breakdown or sparkline) |
| Location | `net-worth-tracker/net-worth-android/`, package `com.isym444.networth` |
| Milestone maths | Ported to Kotlin; no backend changes |
| Auth | Reuse `DASHBOARD_ACCESS_KEY` via the `X-Api-Key` header |
| Approach | Copy and adapt the Countdown Android project (no shared library) |

## Non-goals

Taking snapshots, editing the monthly contribution, Amex transactions, AI commentary, a currency picker,
a sparkline or per-source breakdown, backend changes, a read-only API key. The widget is read-only.

## Architecture and data flow

Same stack as Countdown: Kotlin, Glance, WorkManager, OkHttp, kotlinx-serialization, minSdk 31, unit tests on the JVM only.

1. `RefreshWorker` runs hourly and on demand. It calls `GET {baseUrl}/api/v1/history` with `X-Api-Key`,
   decodes the response and writes `cache.json` atomically (temp file + rename).
2. On any failure (offline, timeout, 401, bad JSON) the cache is left untouched. The widget keeps showing
   the last good data plus a stale or "key rejected" hint.
3. The widget renders from the cache only and never touches the network.
4. Milestone figures are computed at render time from the cached `grandTotal` and `monthlyContribution`.
5. The data changes about once a day, so there is no midnight alarm. Schedule: 60-minute periodic refresh
   (network-constrained, exponential backoff) plus a 6-hour unconstrained re-render as a self-heal.
   `Scheduler.ensureScheduled` is called on each render and is idempotent.

Reused from Countdown with renames: `SettingsStore` (encrypted prefs), `EventCache` → `HistoryCache`,
`Sync`, `ApiClient`, `OpenWithRecovery`, `UrlPolicy`, `Scheduler`, `RefreshWorker`, `RerenderWorker`,
`WidgetUpdater` (tick-in-widget-state re-render), `WidgetPinner`, `SettingsActivity`, `WidgetConfigActivity`.

## API contract used

`GET /api/v1/history` returns `NetWorthHistoryResponse`:

- `snapshots[]` (oldest first): `timestamp`, `currency`, `grandTotal`, and per-source fields.
- `expectedNetWorthAt40/50/60/65`, `expectedNetWorthWithContributionsAt40/50/60/65`,
  `estimatedNetWorth1y`, `monthlyContribution` (all nullable).

The client uses `ignoreUnknownKeys` and treats every projection field as nullable. The widget reads the
last snapshot (latest) and the one before it (for the change line).

## Widget UI

One `GlanceAppWidget` with `SizeMode.Exact`; layout switches on the size the launcher provides.

| Size | Contents |
|---|---|
| Small (~2×2) | Net worth headline, change since previous snapshot, "updated Xh ago" |
| Medium (~4×2) | Headline on the left; next 3 unreached milestones with time and age on the right |
| Large (~4×4) | Headline, 1-year estimate, all 5 milestones, projected net worth at 40/50/60/65 |

Per-widget option (config activity): milestones **with contributions** (default) or **without**.
Currency and symbol come from the snapshot's `currency`.

States: unconfigured → "Open Net Worth to set up"; no cache → "Waiting for first sync";
401 → last data with a "key rejected" hint; stale (last success over ~6h ago) → "updated Xh ago" hint.

Tap target: opens the web dashboard if a web URL is configured, otherwise the settings screen.

## Settings screen

API base URL (default `https://nwapi.isym.uk`), access key (Keystore-backed `EncryptedSharedPreferences`),
optional web dashboard URL, "Save & test", "Add widget to home screen", and a ColorOS battery-settings shortcut.

## Milestone maths

`Projection.monthsToReach(current, target, monthlyContribution)` mirrors `timeToReachPortfolio` in
`net-worth-frontend/src/app/dashboard.component.ts`:

- `balance = balance * 1.07^(1/12) + monthlyContribution` per month, capped at 1200 months.
- Result: `Reached`, `In(months)` or `Never`. Rendered as `Reached (age N)`, `Ny Nm (age N)` or `100y+`.
- Constants live in one `ProjectionConfig`: annual return 0.07, milestones
  `[100000, 250000, 500000, 750000, 1000000]`, birth date 1996‑06‑14. A comment there points at the Angular
  and Spring (`NetWorthHistoryService.BIRTH_DATE`) copies.
- Known difference: JS `setMonth` overflows at month ends while `java.time.plusMonths` clamps, so the displayed
  age can differ by a day around a birthday. This is pinned in a test and not reimplemented.
- The "already reached" check uses `grandTotal >= target`, as the dashboard does.

## Testing

JVM unit tests only:

- **Projection:** expected values produced by running the Angular formula on fixed inputs; covers already reached,
  never (100y+), zero contribution, and age rollover across a birthday.
- **ApiClient (MockWebServer):** 200, 401, timeout, unknown fields, null projections.
- **Cache:** corrupt or missing file loads as empty; write is atomic.
- **Sync:** cache untouched on every failure type.
- **WidgetModel:** row selection for small, medium and large; stale, unconfigured, empty and 401 states;
  with vs without contributions.
- **Settings/URL validation** carried over from Countdown's tests.

## Security notes

- The key is stored only in encrypted prefs and never logged.
- `DASHBOARD_ACCESS_KEY` has no scopes, so a leaked key could also call `POST /api/v1/snapshot` and
  `POST /api/v1/amex/insights`. Accepted for now. Mitigation is rotating the env var. A read-only key
  would be a separate backend change.
- Release signing uses a dedicated keystore with git-ignored `keystore.properties`. Debug builds may allow
  cleartext to the emulator host only, as in Countdown.
- Nothing from the repo `.env` is read or copied into the app.
