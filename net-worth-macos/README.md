# Net Worth — macOS widget

A WidgetKit widget (Japanese text, amounts in 万/億 with ポンド) that mirrors the Android widget: net worth, change,
next-goal progress, milestones, projections and a 2-year trend, from `net-worth-service`.
Spec and plan: `docs/superpowers/`.

## Layout
- `Sources/NetWorthCore`: all logic (money formatting, projections, goal progress, payday countdown, the widget model, API client, cache, sync).
- `Sources/NetWorthViews`: the SwiftUI layouts for the four widget sizes.
- `Sources/LayoutRenderer`: dev-only; `swift run LayoutRenderer renders` draws every size to PNG using synthetic data.
- `App/`: the settings window. `Widget/`: the WidgetKit extension. `project.yml`: the XcodeGen project.

## Build, install
    swift test                 # 148 unit tests, no Xcode project needed
    ./install.sh               # xcodegen + xcodebuild (Apple Development cert, no provisioning profile) + install + open

Needs Xcode, XcodeGen (`brew install xcodegen`) and an Apple Development certificate for team `JKU8L3H56D` in your login
keychain (Xcode → Settings → Accounts creates it). There is no provisioning profile, so the 7-day free-tier expiry (after
which a profile-signed app refuses to launch) doesn't apply; the certificate itself lasts a year (current one: 2027-07-27).
Don't switch to ad-hoc signing: it builds and launches, but macOS denies the widget extension access to the App Group
container, so the widget stays on "not configured" even though the app is configured.

## Use
1. In the app: API URL (default `https://nwapi.isym.uk`), the dashboard access key, optionally the dashboard URL. **Save & test**.
2. Right-click the desktop → **Edit Widgets** → search 純資産 → add a size. Small = headline; medium = + next 3 milestones;
   large = + all milestones and projections; extra-large = two columns with the goal bars, recent changes and the trend chart.
3. Edit Widget → 積立を含める toggles the monthly contribution in the milestone and goal maths.

## Notes
- The widget fetches the API itself about hourly (macOS budgets refreshes). It always renders from the last good cache
  and shows 古いデータ after 6 hours without a successful sync, or a key-rejected hint after a 401/403.
- The key lives in an owner-only (0600) file, `api-key`, in the App Group container shared by the app and widget. It is not
  encrypted at rest: the Keychain's shared access group needs a provisioning profile, which is what a free Apple ID can't
  keep alive. URLs and the last sync status are in the App Group's UserDefaults.
- `NetWorth --self-test` (run the binary inside the app bundle) checks the App Group container and the key file; useful if signing changes.
- After moving from the Keychain build, re-enter the access key once in the app (the old Keychain item isn't migrated).
- The projection assumptions (7% a year, milestones, birth date) are duplicated from the Android widget, the dashboard and
  the backend; change all copies together (see the comment in `Projection.swift`).
