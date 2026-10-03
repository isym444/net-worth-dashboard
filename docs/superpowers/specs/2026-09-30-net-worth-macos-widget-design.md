# Net Worth macOS Widget — Design

Date: 2026-09-30

## Goal

A native macOS WidgetKit widget showing net worth, recent changes, goal progress, milestones, projections and a
2-year trend from the existing `net-worth-service` API. It mirrors the Android widget (`net-worth-android/`) in
content and design and is intended for the user's MacBook Air (macOS 26.4, Xcode 26.6, Swift 6.3).

## Decisions (agreed)

| Topic | Decision |
|---|---|
| Form | Native WidgetKit widget (Edit Widgets, desktop and Notification Center), not a menu-bar app or floating panel |
| Location | `net-worth-tracker/net-worth-macos/` |
| Data | Widget extension fetches `GET /api/v1/history` itself (`X-Api-Key`); a host app is only a settings window |
| Content and design | Same as Android: Japanese text, 万/億 amounts with `ポンド` (never `£`), same palette, same tiers, same goal bars and trend chart |
| Project layout | Swift package `NetWorthCore` (logic, tested with `swift test`) plus an XcodeGen `project.yml` for the app and widget extension |
| Audience | Only the user's MacBook Air, signed with their existing Apple Development identity (personal team); no App Store |
| Backend | No changes |

## Non-goals

iPhone/iPad, notifications, interactive widget buttons, taking snapshots, editing the monthly contribution,
Amex transactions, AI commentary, a currency picker, App Store distribution, a read-only API key.

## Structure

```
net-worth-macos/
  Package.swift            NetWorthCore + NetWorthViews libraries, dev-only LayoutRenderer, tests
  Sources/NetWorthCore/    Money, Projection, GoalProgress, WidgetModel, ApiClient, UrlPolicy, storage, sync, timeline plan
  Sources/NetWorthViews/   SwiftUI layouts for the four sizes, palette, sample data (no WidgetKit)
  Sources/LayoutRenderer/  dev-only: draws every size to PNG with ImageRenderer
  Tests/NetWorthCoreTests/ ported Android tests (same inputs and expected values)
  project.yml              XcodeGen: app target "純資産" + widget extension target
  App/                     SwiftUI settings window
  Widget/                  WidgetKit provider, App Intent, entry view
  install.sh, README.md    build, sign, install, add the widget
```

`NetWorthCore` has no UI and no WidgetKit imports, and `NetWorthViews` has no WidgetKit imports, so logic and layouts are testable and renderable without an Xcode project.

## Data flow

1. The provider's `getTimeline` reads the URL and key from shared storage and calls
   `GET {baseUrl}/api/v1/history` over HTTPS with the `X-Api-Key` header (15 s timeout). Plain HTTP is accepted only
   for `localhost` (testing), mirroring Android's `UrlPolicy`.
2. On success it decodes the response, writes `cache.json` (response plus `fetchedAt`) atomically to the App Group
   container, and builds the entry from the fresh data. Only `id`, `timestamp`, `currency` and `grandTotal` are decoded
   per snapshot, and every snapshot is kept (the chart, change lines and all-time high need the whole history).
3. On any failure (offline, timeout, 401/403, bad JSON) the cache is left untouched and the entry is built from it.
   401/403 sets a "key rejected" flag; no successful fetch for more than 6 hours shows the "out of date" hint.
4. Timeline: one entry every 30 minutes for the next 6 hours (so "N分前に更新" stays accurate) and a refresh request
   after about one hour. macOS budgets refreshes, so actual timing is the system's.
5. The per-widget "with contributions" option is an App Intent configuration (`積立を含める`, default on), like the
   Android widget's option.
6. Tap: opens the dashboard URL if set, otherwise the host app.

## Settings and secrets

The host app has a settings window: API URL (default `https://nwapi.isym.uk`), access key, optional dashboard URL,
**Save & test** (validates, fetches, shows the latest total or the error, then reloads all widget timelines). The key is
stored in the Keychain under an access group shared by the app and the widget; URL and dashboard URL live in the App
Group container. The key is never logged.

**Risk to verify first:** that a personal team can provision an App Group and a shared Keychain access group on macOS.
The first implementation step builds a minimal signed app plus widget extension, checks that both bundles carry the group
entitlements, and runs a command-line self-test of the group container and Keychain group. If it fails, work stops and the
user decides: the fallback is to keep the URL and key in the widget's own configuration (an App Intent text parameter,
edited via Edit Widget) with the cache in the extension's own container.

## Model and formatting

`WidgetModel.build(cache, config, settings, status, now, calendar)` is a line-for-line port of the Kotlin model and
returns `notConfigured`, `empty(message)` or `ready(...)` with the same fields: headline amount and unit, change and
direction, updated text, hint, one-year estimate, assumptions line, milestones, projections, projection table
(with and without contributions), next goal, period changes (7 days, 30 days, 全期間), all-time high, trend points
(last 2 years, thinned to at most 200) and trend span.

- **Money:** `Money.unit` (GBP→ポンド, EUR→ユーロ, USD→ドル, JPY→円, MXN→ペソ, other→` CODE`), `Money.number`, `Money.full`,
  `Money.compact` using 万 and 億 with one decimal; never prints "-0".
- **Projection:** monthly compounding at 7% a year, 100-year cap, birth date 1996-06-14, milestones
  £100k/250k/500k/750k/£1m, exactly as `ProjectionConfig.kt`. The comment listing the duplicate copies of these
  assumptions is extended to include the Swift file.
- **GoalProgress:** the two measures from the Android widget: 資産の寄与 `P(1+r)^n / T` and 時間の進捗 `(n₀−n)/n₀`, with
  the same edge cases (already reached, no contributions → time progress undefined, zero return, unreachable).
  The remaining-time text comes from the integer month loop so it matches the milestone rows.
- **Strings (Japanese):** as on Android, for example 純資産, 目標までの期間, 将来の予想純資産, 全期間, 最高値, 資産の寄与,
  時間の進捗, たった今更新, N分前に更新, 古いデータ — サーバーに接続できません, アプリを開いて設定してください.

## Layouts

| macOS family | Layout |
|---|---|
| systemSmall | Net worth (amount large, unit small), change, updated or hint |
| systemMedium | Left: small summary. Right: next 3 milestones (or "全ての目標を達成しました") |
| systemLarge | Single column: change, one-year estimate, assumptions, all 5 milestones, projections, footer |
| systemExtraLarge | Two columns: left = summary, next-goal bars, 増減の推移 lines, all-time high, footer; right = milestones, projection table (積立あり / 積立なし); trend chart across the bottom when tall enough |

Same palette as Android (light and dark follow the system). The trend chart is drawn with SwiftUI `Path` (no Charts dependency, so the offscreen renderer draws it deterministically): a line plus soft area
fill, points placed by date, green if up over the window, red if down. The next-goal section is one header line
(`10万ポンドまで・あと3年1か月`) and two labelled bars (資産の寄与 in green, 時間の進捗 in blue; 算出不可 when time
progress is undefined). Text never wraps; long text is truncated, not clipped mid-glyph.

## Error and empty states

Same as Android: not configured, waiting for the first sync, key rejected with nothing cached, empty database, plus the
stale and key-rejected hints in the footer. The widget always renders something, never a blank.

## Testing

- `swift test` for `NetWorthCore`: ported Money, Projection, GoalProgress (including the worked example
  P=48,000, T=100,000, C=1,000, a=7% → n≈36.9, 資産の寄与 59.1%, 時間の進捗 53.6%), WidgetModel (states, hints,
  changes, trend, projection table, next goal), UrlPolicy, and cache round-trip; plus a decode test against a trimmed copy
  of the real `/history` response shape (extra fields ignored).
- Layout check: the dev-only `LayoutRenderer` draws each family in light and dark with synthetic sample data to PNG (SwiftUI `ImageRenderer`),
  inspected during development. Real sizes, refresh timing and the desktop look are confirmed from the user's screenshots.
- Build check: `xcodebuild` for the app and extension with the user's signing identity.

## Delivery

The user builds and installs with the documented `xcodebuild` command (or opens the generated project in Xcode),
runs the app once to enter the URL and key, then adds "純資産" from Edit Widgets. `README.md` documents this, the
signing setup, and the shared-assumptions note.

## Open items

None blocking. The App Group and Keychain verification is the first plan step and may change only the storage detail
described above.
