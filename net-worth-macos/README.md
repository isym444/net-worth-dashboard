# Net Worth — macOS widget

A WidgetKit widget (Japanese text, amounts in 万/億 with ポンド) that mirrors the Android widget: net worth, change,
next-goal progress, milestones, projections and a 2-year trend, from `net-worth-service`.
Spec and plan: `docs/superpowers/`.

## Layout
- `Sources/NetWorthCore`: all logic (money formatting, projections, goal progress, the widget model, API client, cache, sync).
- `Sources/NetWorthViews`: the SwiftUI layouts for the four widget sizes.
- `Sources/LayoutRenderer`: dev-only; `swift run LayoutRenderer renders` draws every size to PNG using synthetic data.
- `App/`: the settings window. `Widget/`: the WidgetKit extension. `project.yml`: the XcodeGen project.

## Build, install
    swift test                 # 116 unit tests, no Xcode project needed
    ./install.sh               # xcodegen + xcodebuild (signed with your Apple Development identity) + install + open

Needs Xcode, XcodeGen (`brew install xcodegen`) and an Apple ID added under Xcode → Settings → Accounts
(team `JKU8L3H56D`). The first build may register the App Group and Keychain group for the team.

## Use
1. In the app: API URL (default `https://nwapi.isym.uk`), the dashboard access key, optionally the dashboard URL. **Save & test**.
2. Right-click the desktop → **Edit Widgets** → search 純資産 → add a size. Small = headline; medium = + next 3 milestones;
   large = + all milestones and projections; extra-large = two columns with the goal bars, recent changes and the trend chart.
3. Edit Widget → 積立を含める toggles the monthly contribution in the milestone and goal maths.

## Notes
- The widget fetches the API itself about hourly (macOS budgets refreshes). It always renders from the last good cache
  and shows 古いデータ after 6 hours without a successful sync, or a key-rejected hint after a 401/403.
- The key is stored only in the Keychain (shared access group); URLs and the last sync status are in the App Group.
- `NetWorth --self-test` (run the binary inside the app bundle) checks the App Group and Keychain group; useful if signing changes.
- The projection assumptions (7% a year, milestones, birth date) are duplicated from the Android widget, the dashboard and
  the backend; change all copies together (see the comment in `Projection.swift`).
