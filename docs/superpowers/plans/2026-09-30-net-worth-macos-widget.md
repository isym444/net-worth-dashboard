# Net Worth macOS Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native macOS WidgetKit widget, mirroring the Android widget, that shows net worth, recent changes, goal progress, milestones, projections and a 2-year trend from `net-worth-service`.

**Architecture:** A Swift package (`NetWorthCore` for all logic, `NetWorthViews` for the SwiftUI layouts, a dev-only `LayoutRenderer` that draws each size to PNG) plus an XcodeGen project with a small host app (settings window) and a WidgetKit extension. The extension fetches `GET /api/v1/history` itself every hour, caches the response in a shared App Group container, and builds a timeline of entries from the cache. The access key lives in a shared Keychain group.

**Tech Stack:** Swift 6.3 (Swift 5 language mode), SwiftUI, WidgetKit, AppIntents, XCTest, XcodeGen 2.x, Xcode 26.6, macOS 14+ deployment target.

**Spec:** `docs/superpowers/specs/2026-09-30-net-worth-macos-widget-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Location:** everything lives in `net-worth-tracker/net-worth-macos/`. No backend changes. Do not modify `net-worth-android/`, `net-worth-service/` or `net-worth-frontend/`.
- **Platform:** macOS deployment target 14.0; Swift 5 language mode (`swiftLanguageModes: [.v5]`); toolchain Xcode 26.6 / Swift 6.3.3.
- **Language and units:** all widget text is Japanese. Amounts use 万/億 with `ポンド` and never `£`. The settings window (host app) is in English, like the Android settings screen.
- **Data:** `GET {baseUrl}/api/v1/history` with header `X-Api-Key`, 15 s request timeout and 20 s resource timeout. HTTPS is required except for `localhost`, `127.0.0.1` and `10.0.2.2`.
- **Secrets:** the key is never logged, never written to `UserDefaults`, and lives only in the Keychain.
- **Assumptions (must equal Android's `ProjectionConfig.kt`):** 7% a year compounded monthly, 1200-month cap, birth date 1996-06-14, milestones 100k / 250k / 500k / 750k / 1m.
- **Freshness:** data is "out of date" after 6 hours without a successful sync. Timeline: 12 entries 30 minutes apart, refresh requested after 1 hour.
- **Signing:** automatic signing with the user's Apple Development identity, team `JKU8L3H56D`. Bundle IDs `com.isym444.networth` (app) and `com.isym444.networth.widget` (extension). App Group `$(TeamIdentifierPrefix)com.isym444.networth`; Keychain group `$(AppIdentifierPrefix)com.isym444.networth`. No team ID appears in Swift source (both bundles read the group names from Info.plist keys `NWAppGroup` and `NWKeychainGroup`).
- **Git:** the user has not yet asked for any commits in this work. The repo is on `main` (the Android widget was merged in PR #1). Before the first commit, ask whether to commit and propose creating a `macos-widget` branch first; never commit to `main` directly. Only ever `git add` files under `net-worth-macos/` and `docs/superpowers/` for this work (the spec and this plan are currently untracked). Commit steps below say what to commit once that is approved.
- **Every task ends green:** run `swift test` (from `net-worth-macos/`) and expect 0 failures before moving on.

## Review Focus

Inputs and conditions the spec implies but that no happy-path test would exercise. Each line has a test in the task that owns the code (named in brackets).

1. **Snapshot timestamps with microseconds, or unparseable ones.** The real API sends `2026-08-01T07:00:03.533248Z` for some rows and `2020-07-01T12:00:00Z` for others; a bad one must not crash or blank the chart. [Task 5: `testParsesTimestampsWithAndWithoutFractionalSeconds`, `testASingleSnapshotHasNoPeriodChangesAndUnparseableTimestampsAreIgnored`]
2. **A pasted access key with a trailing newline, inner spaces or control characters.** The newline is trimmed; spaces and control characters are rejected with a clear message rather than sent. [Task 4: `SettingsValidatorTests`; Task 6: `testAKeyWithControlCharactersIsNotConfigured`]
3. **The URL points at some other service** (HTML, `{}`, `{"status":"ok"}`, an empty body) or uses plain `http://` on a real host. It must fail with "Unexpected response" or be rejected, never render an empty widget as if it were data. [Task 4: `testAJsonBodyWithoutSnapshotsIsNotAHistoryResponse`, `testRejectsPlainHttpToARealHost`; Task 6: `testGarbageAndNonHistoryBodiesAreDecodingErrors`]
4. **The Mac sleeps or is offline for hours.** The widget keeps showing the last data, the "updated" text advances, and the out-of-date hint appears by itself once the data is 6 hours old, without any refresh. [Task 7: `testUpdatedTextAdvancesAndTheStaleHintAppearsOnItsOwn`; Task 6: `testAFailureLeavesTheCacheAlone`]
5. **The App Group or Keychain group is unavailable** (a signing problem). The widget must render "アプリを開いて設定してください", not crash. [Task 7: `testWithoutAnAppGroupItFallsBackAndTheWidgetShowsNotConfigured`; Task 1 decides whether the groups work at all]
6. **Long histories and odd totals** (hundreds of daily snapshots, zero, negative or huge net worth, a missing currency). The chart is thinned to 200 points; formatting never prints `-0`. [Task 2 and Task 5: `testTrendIsThinnedEvenlyKeepingTheFirstAndLastPoint`, `testZeroNetWorthDoesNotCrash`, `testNegativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions`, `testABlankCurrencyHasNoUnit`]

---

## File Structure

```
net-worth-macos/
  .gitignore
  Package.swift                          Task 1 (Core only), extended in Task 8
  project.yml                            Task 1, extended in Tasks 8-9
  install.sh                             Task 10
  README.md                              Task 10
  Sources/NetWorthCore/                  no UI, no WidgetKit imports
    SharedIds.swift                      Task 1   Info.plist key names for the shared groups
    Money.swift                          Task 2   ポンド / 万 / 億 formatting
    Projection.swift                     Task 3   ProjectionConfig, Reach, months-to-reach, age
    GoalProgress.swift                   Task 3   the two next-goal measures
    Models.swift                         Task 4   Snapshot, HistoryResponse, CacheSnapshot, WidgetConfig
    Settings.swift                       Task 4   AppSettings, SyncStatus, UrlPolicy, SettingsValidator
    WidgetModel.swift                    Task 5   (cache, config, settings, status, now) -> WidgetState
    ApiClient.swift                      Task 6   ApiError, HistorySource, ApiClient
    Storage.swift                        Task 6   HistoryCache, SettingsStore, SecretStore, KeychainSecretStore
    Sync.swift                           Task 6   refresh the cache from the API
    SharedEnvironment.swift              Task 7   the settings store + cache the app and widget share
    EntryPlan.swift                      Task 7   timeline dates and states
  Sources/NetWorthViews/                 SwiftUI only (no WidgetKit)
    Palette.swift, Components.swift, NetWorthWidgetView.swift, SampleData.swift   Task 8
  Sources/LayoutRenderer/main.swift      Task 8   dev-only PNG renderer
  Tests/NetWorthCoreTests/               one test file per source file, ported from the Android tests
  App/                                   Task 1 (spike), Task 10 (settings window)
  Widget/                                Task 1 (placeholder), Task 9 (real widget)
```

---

### Task 1: Scaffold, signing and shared-group spike

This task decides whether the design's storage works with the user's personal signing team. Everything else depends on it. **If the verification in Steps 8-10 fails, stop and report to the user; do not continue.**

**Files:**
- Create: `net-worth-macos/.gitignore`
- Create: `net-worth-macos/Package.swift` (Core-only version)
- Create: `net-worth-macos/Sources/NetWorthCore/SharedIds.swift`
- Create: `net-worth-macos/Tests/NetWorthCoreTests/SharedIdsTests.swift`
- Create: `net-worth-macos/project.yml`
- Create: `net-worth-macos/App/NetWorthApp.swift`, `net-worth-macos/App/SelfTest.swift`
- Create: `net-worth-macos/Widget/PlaceholderWidget.swift`

**Interfaces:**
- Produces: `SharedIds.keychainService: String`, `SharedIds.appGroup(_ bundle: Bundle = .main) -> String?`, `SharedIds.keychainGroup(_ bundle: Bundle = .main) -> String?` (used by Tasks 6, 7, 9, 10). `xcodegen generate` + `xcodebuild` recipe (used by every later build step).

- [ ] **Step 1: Create the directory and `.gitignore`**

```bash
cd "/Users/isym444/Desktop/Desktop - IanのMacBook Air/programming/net-worth-tracker"
mkdir -p net-worth-macos/Sources/NetWorthCore net-worth-macos/Tests/NetWorthCoreTests net-worth-macos/App net-worth-macos/Widget
cat > net-worth-macos/.gitignore <<'EOF'
.build/
build/
renders/
NetWorth.xcodeproj/
App/Info.plist
App/NetWorth.entitlements
Widget/Info.plist
Widget/NetWorthWidget.entitlements
*.xcuserstate
.DS_Store
EOF
```

- [ ] **Step 2: Write the Core-only `Package.swift`**

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NetWorthMac",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "NetWorthCore", targets: ["NetWorthCore"]),
    ],
    targets: [
        .target(name: "NetWorthCore"),
        .testTarget(name: "NetWorthCoreTests", dependencies: ["NetWorthCore"]),
    ],
    swiftLanguageModes: [.v5]
)
```

- [ ] **Step 3: Write the failing test for `SharedIds`**

```swift
import XCTest
@testable import NetWorthCore

final class SharedIdsTests: XCTestCase {
    func testTheInfoPlistKeysAreAbsentFromAPlainTestBundleAndTheServiceNameIsFixed() {
        let bundle = Bundle(for: SharedIdsTests.self)
        XCTAssertNil(SharedIds.appGroup(bundle))
        XCTAssertNil(SharedIds.keychainGroup(bundle))
        XCTAssertEqual(SharedIds.keychainService, "com.isym444.networth")
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `cd net-worth-macos && swift test 2>&1 | tail -15`
Expected: FAIL with `cannot find 'SharedIds' in scope`.

- [ ] **Step 5: Implement `SharedIds`**

```swift
import Foundation

/// Identifiers shared by the app and the widget extension. Both read the same Info.plist keys (filled in from the signing
/// team by the build settings in project.yml), so no team ID is hard-coded in source.
public enum SharedIds {
    public static let keychainService = "com.isym444.networth"

    public static func appGroup(_ bundle: Bundle = .main) -> String? { bundle.object(forInfoDictionaryKey: "NWAppGroup") as? String }
    public static func keychainGroup(_ bundle: Bundle = .main) -> String? { bundle.object(forInfoDictionaryKey: "NWKeychainGroup") as? String }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 1 test, with 0 failures`.

- [ ] **Step 7: Write `project.yml` and the spike sources**

`net-worth-macos/project.yml`:

```yaml
name: NetWorth
options:
  bundleIdPrefix: com.isym444
  deploymentTarget:
    macOS: "14.0"
settings:
  base:
    DEVELOPMENT_TEAM: JKU8L3H56D
    CODE_SIGN_STYLE: Automatic
    SWIFT_VERSION: "5.0"
    ENABLE_HARDENED_RUNTIME: YES
    MARKETING_VERSION: "1.0"
    CURRENT_PROJECT_VERSION: "1"
packages:
  NetWorthMac:
    path: .
targets:
  NetWorth:
    type: application
    platform: macOS
    sources: [App]
    dependencies:
      - package: NetWorthMac
        product: NetWorthCore
      - target: NetWorthWidget
    entitlements:
      path: App/NetWorth.entitlements
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.network.client: true
        com.apple.security.application-groups:
          - $(TeamIdentifierPrefix)com.isym444.networth
        keychain-access-groups:
          - $(AppIdentifierPrefix)com.isym444.networth
    info:
      path: App/Info.plist
      properties:
        CFBundleDisplayName: 純資産
        LSApplicationCategoryType: public.app-category.finance
        NWAppGroup: $(TeamIdentifierPrefix)com.isym444.networth
        NWKeychainGroup: $(AppIdentifierPrefix)com.isym444.networth
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.isym444.networth
        PRODUCT_NAME: NetWorth
  NetWorthWidget:
    type: app-extension
    platform: macOS
    sources: [Widget]
    dependencies:
      - package: NetWorthMac
        product: NetWorthCore
    entitlements:
      path: Widget/NetWorthWidget.entitlements
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.network.client: true
        com.apple.security.application-groups:
          - $(TeamIdentifierPrefix)com.isym444.networth
        keychain-access-groups:
          - $(AppIdentifierPrefix)com.isym444.networth
    info:
      path: Widget/Info.plist
      properties:
        CFBundleDisplayName: 純資産
        NWAppGroup: $(TeamIdentifierPrefix)com.isym444.networth
        NWKeychainGroup: $(AppIdentifierPrefix)com.isym444.networth
        NSExtension:
          NSExtensionPointIdentifier: com.apple.widgetkit-extension
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.isym444.networth.widget
        PRODUCT_NAME: NetWorthWidget
schemes:
  NetWorth:
    build:
      targets:
        NetWorth: all
```

`net-worth-macos/App/NetWorthApp.swift`:

```swift
import SwiftUI

@main
struct NetWorthApp: App {
    init() {
        // `NetWorth --self-test` checks the App Group and Keychain group from the command line, then exits.
        if CommandLine.arguments.contains("--self-test") { exit(SelfTest.run()) }
    }

    var body: some Scene {
        WindowGroup("純資産") { Text("純資産").padding(40) }
    }
}
```

`net-worth-macos/App/SelfTest.swift` (raw Security calls; the real Keychain wrapper arrives in Task 6):

```swift
import Foundation
import NetWorthCore
import Security

enum SelfTest {
    static func run() -> Int32 {
        var ok = true
        func check(_ name: String, _ pass: Bool, _ detail: String = "") {
            print("\(pass ? "PASS" : "FAIL") \(name) \(detail)")
            if !pass { ok = false }
        }

        let group = SharedIds.appGroup()
        let keychainGroup = SharedIds.keychainGroup()
        check("Info.plist NWAppGroup expands", group?.isEmpty == false && group?.contains("$") == false, group ?? "nil")
        check("Info.plist NWKeychainGroup expands", keychainGroup?.isEmpty == false && keychainGroup?.contains("$") == false, keychainGroup ?? "nil")

        let container = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
        check("App Group container exists", container != nil, container?.path ?? "nil")

        let defaults = group.flatMap { UserDefaults(suiteName: $0) }
        defaults?.set("hello", forKey: "selftest")
        check("group UserDefaults round trip", defaults?.string(forKey: "selftest") == "hello")
        defaults?.removeObject(forKey: "selftest")

        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.isym444.networth.selftest",
            kSecAttrAccount as String: "selftest",
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let keychainGroup { query[kSecAttrAccessGroup as String] = keychainGroup }
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = Data("selftest-key".utf8)
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        check("Keychain group write", addStatus == errSecSuccess, "status \(addStatus)")
        var read = query
        read[kSecReturnData as String] = true
        var out: CFTypeRef?
        let readStatus = SecItemCopyMatching(read as CFDictionary, &out)
        check("Keychain group read", readStatus == errSecSuccess && (out as? Data).flatMap { String(data: $0, encoding: .utf8) } == "selftest-key", "status \(readStatus)")
        SecItemDelete(query as CFDictionary)

        print(ok ? "SELF-TEST OK" : "SELF-TEST FAILED")
        return ok ? 0 : 1
    }
}
```

`net-worth-macos/Widget/PlaceholderWidget.swift` (replaced in Task 9; only here so the extension builds):

```swift
import SwiftUI
import WidgetKit

struct PlaceholderEntry: TimelineEntry { let date: Date }

struct PlaceholderProvider: TimelineProvider {
    func placeholder(in context: Context) -> PlaceholderEntry { PlaceholderEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (PlaceholderEntry) -> Void) { completion(PlaceholderEntry(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<PlaceholderEntry>) -> Void) {
        completion(Timeline(entries: [PlaceholderEntry(date: Date())], policy: .never))
    }
}

@main
struct PlaceholderWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "PlaceholderWidget", provider: PlaceholderProvider()) { _ in
            Text("純資産").containerBackground(.background, for: .widget)
        }
        .configurationDisplayName("純資産")
        .supportedFamilies([.systemSmall])
    }
}
```

- [ ] **Step 8: Generate the project and build with signing**

```bash
cd net-worth-macos && xcodegen generate && \
xcodebuild -project NetWorth.xcodeproj -scheme NetWorth -configuration Release \
  -derivedDataPath build -allowProvisioningUpdates build 2>&1 | tail -25
```

Expected: `** BUILD SUCCEEDED **`. If it fails:
- `No Account for Team "JKU8L3H56D"` / no provisioning profile: open Xcode → Settings → Accounts, add the Apple ID `samiryep@yahoo.com`, then rerun. (`security find-identity -v -p codesigning` shows the identity is installed; the profile needs the account.)
- `Provisioning profile ... doesn't include the ... entitlement` for `application-groups` or `keychain-access-groups`: the personal team cannot provision that capability. Stop and go to Step 10.
- `$(TeamIdentifierPrefix)` appears literally (Step 9 shows `$`): replace those two Info.plist values in `project.yml` with the build settings `NW_APP_GROUP: JKU8L3H56D.com.isym444.networth` and `NW_KEYCHAIN_GROUP: JKU8L3H56D.com.isym444.networth` (added under each target's `settings: base:`), referenced as `$(NW_APP_GROUP)` / `$(NW_KEYCHAIN_GROUP)`.

- [ ] **Step 9: Verify the entitlements of both bundles and run the self-test**

```bash
cd net-worth-macos
APP=build/Build/Products/Release/NetWorth.app
for b in "$APP" "$APP/Contents/PlugIns/NetWorthWidget.appex"; do
  echo "== $b"; codesign -d --entitlements :- "$b" 2>/dev/null | plutil -p -
done
"$APP/Contents/MacOS/NetWorth" --self-test; echo "exit=$?"
```

Expected: both bundles list `com.apple.security.application-groups => ["JKU8L3H56D.com.isym444.networth"]` and `keychain-access-groups` containing `JKU8L3H56D.com.isym444.networth`, plus `com.apple.security.app-sandbox => 1` and `com.apple.security.network.client => 1`; the self-test prints five `PASS` lines, `SELF-TEST OK` and `exit=0`.

- [ ] **Step 10: Decision gate**

If Step 9 passes: continue. If it does not pass and cannot be fixed by the troubleshooting above, **stop and tell the user**: the personal team cannot use a shared App Group or Keychain group on macOS. The documented fallback is to store the URL and key in the widget's own configuration (an App Intent text parameter, edited via Edit Widget) instead of the host app, with the cache in the widget extension's own container. Do not start that redesign without the user's approval, because it changes the spec.

- [ ] **Step 11: Commit (once the user has approved committing)**

```bash
git add net-worth-macos/.gitignore net-worth-macos/Package.swift net-worth-macos/project.yml net-worth-macos/Sources net-worth-macos/Tests net-worth-macos/App/NetWorthApp.swift net-worth-macos/App/SelfTest.swift net-worth-macos/Widget/PlaceholderWidget.swift
git commit -m "macos: scaffold package, XcodeGen project and signing spike"
```

---

### Task 2: Money formatting

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/Money.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/MoneyTests.swift`

**Interfaces:**
- Produces: `Money.unit(_ code: String) -> String`, `Money.number(_ amount: Double) -> String`, `Money.full(_ amount: Double, unit: String) -> String`, `Money.compact(_ amount: Double, unit: String) -> String`, and internal `Money.roundHalfUp(_:) -> Int64` (used by Task 5).

- [ ] **Step 1: Write the failing test**

```swift
import XCTest
@testable import NetWorthCore

final class MoneyTests: XCTestCase {
    func testUnits() {
        XCTAssertEqual(Money.unit("GBP"), "ポンド")
        XCTAssertEqual(Money.unit("gbp"), "ポンド")
        XCTAssertEqual(Money.unit("EUR"), "ユーロ")
        XCTAssertEqual(Money.unit("USD"), "ドル")
        XCTAssertEqual(Money.unit("JPY"), "円")
        XCTAssertEqual(Money.unit("MXN"), "ペソ")
    }

    func testUnknownCurrenciesUseTheirCodeAndBlankUsesNothing() {
        XCTAssertEqual(Money.unit("CHF"), " CHF")
        XCTAssertEqual(Money.unit(""), "")
        XCTAssertEqual(Money.unit("  "), "")
    }

    func testFullFormatsWholeUnitsWithGrouping() {
        XCTAssertEqual(Money.full(184_320.25, unit: "ポンド"), "184,320ポンド")
        XCTAssertEqual(Money.full(0, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.full(1_234_567, unit: "ドル"), "1,234,567ドル")
        XCTAssertEqual(Money.full(1_234, unit: " CHF"), "1,234 CHF")
    }

    func testFullHandlesNegativesAndNeverPrintsMinusZero() {
        XCTAssertEqual(Money.full(-1_234.6, unit: "ポンド"), "-1,235ポンド")
        XCTAssertEqual(Money.full(-1_500, unit: "ポンド"), "-1,500ポンド")
        XCTAssertEqual(Money.full(-0.2, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.number(-1_234.6), "-1,235")
    }

    func testCompactUsesJapaneseManAndOkuUnits() {
        XCTAssertEqual(Money.compact(100_000, unit: "ポンド"), "10万ポンド")
        XCTAssertEqual(Money.compact(250_000, unit: "ポンド"), "25万ポンド")
        XCTAssertEqual(Money.compact(845_300, unit: "ポンド"), "84.5万ポンド")
        XCTAssertEqual(Money.compact(65_000, unit: "ポンド"), "6.5万ポンド")
        XCTAssertEqual(Money.compact(1_000_000, unit: "ポンド"), "100万ポンド")
        XCTAssertEqual(Money.compact(1_700_000, unit: "ポンド"), "170万ポンド")
        XCTAssertEqual(Money.compact(1_234_567, unit: "ポンド"), "123.5万ポンド")
        XCTAssertEqual(Money.compact(10_000_000, unit: "ポンド"), "1000万ポンド")
        XCTAssertEqual(Money.compact(100_000_000, unit: "ポンド"), "1億ポンド")
        XCTAssertEqual(Money.compact(150_000_000, unit: "ポンド"), "1.5億ポンド")
    }

    func testCompactSmallAndNegativeValues() {
        XCTAssertEqual(Money.compact(999, unit: "ポンド"), "999ポンド")
        XCTAssertEqual(Money.compact(5_000, unit: "ポンド"), "5,000ポンド")
        XCTAssertEqual(Money.compact(0, unit: "ポンド"), "0ポンド")
        XCTAssertEqual(Money.compact(-250_000, unit: "ポンド"), "-25万ポンド")
        XCTAssertEqual(Money.compact(-0.2, unit: "ポンド"), "0ポンド")
    }

    func testCompactRoundsUpAcrossUnitBoundaries() {
        XCTAssertEqual(Money.compact(99_999, unit: "ポンド"), "10万ポンド")
        XCTAssertEqual(Money.compact(99_999_999, unit: "ポンド"), "1億ポンド")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd net-worth-macos && swift test --filter MoneyTests 2>&1 | tail -8`
Expected: FAIL with `cannot find 'Money' in scope`.

- [ ] **Step 3: Implement `Money`**

```swift
import Foundation

/// Currency formatting in Japanese units: "184,320ポンド", "10万ポンド", "1億ポンド". Never uses £/$/€.
public enum Money {
    /// The currency as a Japanese suffix appended after the amount. Unknown currencies use their code with a
    /// leading space (" CHF"); blank gets no unit.
    public static func unit(_ code: String) -> String {
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch c {
        case "": return ""
        case "GBP": return "ポンド"
        case "EUR": return "ユーロ"
        case "USD": return "ドル"
        case "JPY": return "円"
        case "MXN": return "ペソ"
        default: return " \(c)"
        }
    }

    /// Whole units with grouping and no currency: "184,320", "-1,235". Never prints "-0".
    public static func number(_ amount: Double) -> String {
        let r = roundHalfUp(amount)
        return (r < 0 ? "-" : "") + grouped(abs(r))
    }

    /// Whole units with grouping: "184,320ポンド", "-1,235ポンド".
    public static func full(_ amount: Double, unit: String) -> String { number(amount) + unit }

    /// Short form in Japanese units: "10万ポンド", "6.5万ポンド", "100万ポンド", "1億ポンド", "999ポンド".
    /// 万 = 10,000 and 億 = 100,000,000.
    public static func compact(_ amount: Double, unit: String) -> String {
        let a = abs(amount)
        let wan = decimal(a / 10_000, scale: 1)
        let body: String
        if wan >= 10_000 {
            body = plain(decimal(a / 100_000_000, scale: 2)) + "億"
        } else if wan > 0 && a >= 10_000 {
            body = plain(wan) + "万"
        } else {
            body = grouped(roundHalfUp(a))
        }
        let sign = (amount < 0 && body != "0") ? "-" : ""
        return sign + body + unit
    }

    /// Rounds half up (toward positive infinity), like Kotlin's `roundToLong`.
    static func roundHalfUp(_ x: Double) -> Int64 { Int64((x + 0.5).rounded(.down)) }

    static func grouped(_ n: Int64) -> String {
        let digits = String(n)
        var out = ""
        for (i, ch) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(ch)
        }
        return out
    }

    /// Rounds the shortest decimal representation of `x` half up to `scale` places (like Java's `BigDecimal.valueOf`).
    private static func decimal(_ x: Double, scale: Int) -> Decimal {
        var d = Decimal(string: String(x)) ?? Decimal(x)
        var r = Decimal()
        NSDecimalRound(&r, &d, scale, .plain)
        return r
    }

    private static func plain(_ d: Decimal) -> String { NSDecimalNumber(decimal: d).stringValue }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 8 tests, with 0 failures` (7 Money + 1 SharedIds).

- [ ] **Step 5: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/Money.swift net-worth-macos/Tests/NetWorthCoreTests/MoneyTests.swift
git commit -m "macos: add Japanese money formatting"
```

---

### Task 3: Projection and goal progress

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/Projection.swift`, `net-worth-macos/Sources/NetWorthCore/GoalProgress.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/TestSupport.swift`, `ProjectionTests.swift`, `GoalProgressTests.swift`

**Interfaces:**
- Produces: `ProjectionConfig.{annualReturn, maxMonths, milestones, birthDate}`; `enum Reach: Equatable { reached(age:), inMonths(_:age:), never; func text() -> String }`; `Projection.monthsToReach(current:target:monthlyContribution:maxMonths:) -> Int?`, `Projection.reach(current:target:monthlyContribution:today:calendar:) -> Reach`, `Projection.ageAfterMonths(today:months:calendar:) -> Int`; `struct GoalProgress: Equatable { monthsRemaining: Double?, growthShare: Double, timeShare: Double? }` with `GoalProgress.compute(p:t:c:annualReturn:)`. Test helper `T.calendar`, `T.date(_:_:_:)`, `T.iso(_:)` (used by Tasks 5-7).

- [ ] **Step 1: Write the failing tests**

`Tests/NetWorthCoreTests/TestSupport.swift`:

```swift
import Foundation

/// A UTC Gregorian calendar and helpers so tests do not depend on the machine's time zone.
enum T {
    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    static func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d))!
    }

    static func iso(_ date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: date)
    }
}
```

`Tests/NetWorthCoreTests/ProjectionTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class ProjectionTests: XCTestCase {
    private let today = T.date(2026, 9, 30)

    private func reach(_ current: Double, _ target: Double, _ contribution: Double) -> Reach {
        Projection.reach(current: current, target: target, monthlyContribution: contribution, today: today, calendar: T.calendar)
    }

    func testMatchesDashboardWithoutContributions() {
        XCTAssertEqual(reach(50_000, 100_000, 0), .inMonths(123, age: 40))
        XCTAssertEqual(reach(50_000, 100_000, 0).text(), "10年3か月 (40歳)")
        XCTAssertEqual(reach(184_320, 250_000, 0).text(), "4年7か月 (34歳)")
        XCTAssertEqual(reach(184_320, 1_000_000, 0).text(), "25年0か月 (55歳)")
    }

    func testMatchesDashboardWithContributions() {
        XCTAssertEqual(reach(50_000, 100_000, 1_000), .inMonths(36, age: 33))
        XCTAssertEqual(reach(50_000, 100_000, 1_000).text(), "3年0か月 (33歳)")
        XCTAssertEqual(reach(184_320, 250_000, 1_000).text(), "2年6か月 (32歳)")
        XCTAssertEqual(reach(184_320, 1_000_000, 1_500).text(), "15年4か月 (45歳)")
    }

    func testAlreadyReachedIncludesEqualAndAbove() {
        XCTAssertEqual(reach(100_000, 100_000, 0), .reached(age: 30))
        XCTAssertEqual(reach(250_000, 100_000, 0).text(), "達成 (30歳)")
    }

    func testJustUnderTheTargetTakesOneMonth() {
        XCTAssertEqual(reach(99_999.99, 100_000, 0).text(), "0年1か月 (30歳)")
    }

    func testNeverWhenNotReachableWithinTheCap() {
        XCTAssertEqual(reach(1_000, 1_000_000, 0), .never)
        XCTAssertEqual(reach(1_000, 1_000_000, 0).text(), "100年以上")
        XCTAssertEqual(reach(0, 100_000, 0).text(), "100年以上")
        XCTAssertNil(Projection.monthsToReach(current: 0, target: 100_000, monthlyContribution: 0))
    }

    func testNegativeNetWorthWithNoContributionNeverReaches() {
        XCTAssertEqual(reach(-5_000, 100_000, 0), .never)
    }

    func testNegativeNetWorthWithAContributionEventuallyReaches() {
        guard case .inMonths = reach(-5_000, 100_000, 1_000) else { return XCTFail("expected inMonths") }
    }

    func testMonthsToReachIsZeroWhenAlreadyThere() {
        XCTAssertEqual(Projection.monthsToReach(current: 100_000, target: 100_000, monthlyContribution: 0), 0)
    }

    func testAgeRollsOverOnTheBirthday() {
        // Birthday is 14 June: the day before is still 29, the day itself is 30.
        let c = T.calendar
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 0, calendar: c), 29)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 14), months: 0, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 1, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 12, calendar: c), 30)
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 6, 13), months: 13, calendar: c), 31)
    }

    func testMonthEndClampDoesNotChangeTheAge() {
        // 31 Jan + 1 month clamps to 28 Feb (as java.time does); age is 29 either way.
        XCTAssertEqual(Projection.ageAfterMonths(today: T.date(2026, 1, 31), months: 1, calendar: T.calendar), 29)
    }

    func testMilestoneListMatchesTheDashboard() {
        XCTAssertEqual(ProjectionConfig.milestones, [100_000, 250_000, 500_000, 750_000, 1_000_000])
    }
}
```

`Tests/NetWorthCoreTests/GoalProgressTests.swift` (includes the worked example from the design: P=48,000, T=100,000, C=1,000, a=7% gives n≈36.9 months, 資産の寄与 59.1%, 時間の進捗 53.6%):

```swift
import XCTest
@testable import NetWorthCore

final class GoalProgressTests: XCTestCase {
    func testMatchesTheWorkedExample() {
        let g = GoalProgress.compute(p: 48_000, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertEqual(g.monthsRemaining!, 36.9, accuracy: 0.05)
        XCTAssertEqual(g.growthShare, 0.591, accuracy: 0.001)      // money progress would be 48.0%
        XCTAssertEqual(g.timeShare!, 0.536, accuracy: 0.001)
    }

    func testAlreadyReachedIsEverythingAt100Percent() {
        XCTAssertEqual(GoalProgress.compute(p: 120_000, t: 100_000, c: 1_000, annualReturn: 0.07),
                       GoalProgress(monthsRemaining: 0, growthShare: 1, timeShare: 1))
    }

    func testWithoutContributionsItIsAllYourExistingMoneyAndTimeProgressIsUndefined() {
        let g = GoalProgress.compute(p: 48_000, t: 100_000, c: 0, annualReturn: 0.07)
        XCTAssertEqual(g.growthShare, 1, accuracy: 1e-9)
        XCTAssertNil(g.timeShare)
        XCTAssertEqual(g.monthsRemaining!, log(100_000.0 / 48_000.0) / log(1.07) * 12, accuracy: 0.01)
    }

    func testWithoutReturnItIsMoneyProgressAndStraightLineMonths() {
        let g = GoalProgress.compute(p: 40_000, t: 100_000, c: 1_000, annualReturn: 0)
        XCTAssertEqual(g.monthsRemaining!, 60, accuracy: 1e-9)
        XCTAssertEqual(g.growthShare, 0.4, accuracy: 1e-9)
        XCTAssertEqual(g.timeShare!, 0.4, accuracy: 1e-9)
    }

    func testNoReturnAndNoContributionsCanNeverReachIt() {
        let g = GoalProgress.compute(p: 40_000, t: 100_000, c: 0, annualReturn: 0)
        XCTAssertNil(g.monthsRemaining)
        XCTAssertNil(g.timeShare)
    }

    func testStartingFromNothingIsZeroTimeProgressAndZeroGrowthShare() {
        let g = GoalProgress.compute(p: 0, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertEqual(g.timeShare!, 0, accuracy: 1e-9)
        XCTAssertEqual(g.growthShare, 0, accuracy: 1e-9)
        XCTAssertNotNil(g.monthsRemaining)
    }

    func testDebtBeyondWhatContributionsCanCatchUpIsUnreachable() {
        let g = GoalProgress.compute(p: -500_000, t: 100_000, c: 1_000, annualReturn: 0.07)
        XCTAssertNil(g.monthsRemaining)
        XCTAssertEqual(g.growthShare, 0)
    }

    func testBothMeasuresGrowAsTheSavingsGrow() {
        let runs = [5_000.0, 20_000, 50_000, 90_000].map { GoalProgress.compute(p: $0, t: 100_000, c: 1_000, annualReturn: 0.07) }
        XCTAssertEqual(runs.map { $0.growthShare }, runs.map { $0.growthShare }.sorted())
        XCTAssertEqual(runs.map { $0.timeShare! }, runs.map { $0.timeShare! }.sorted())
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:" | head -5`
Expected: FAIL with `cannot find 'Projection' in scope` / `cannot find 'GoalProgress' in scope`.

- [ ] **Step 3: Implement `Projection.swift`**

```swift
import Foundation

/// Projection assumptions. These duplicate values that also live elsewhere; change all copies together:
///  - net-worth-android/.../domain/ProjectionConfig.kt
///  - net-worth-frontend/src/app/dashboard.component.ts: assumedAnnualReturn, netWorthMilestones, birthDate, maxProjectionMonths
///  - net-worth-service PortfolioProjectionService.ANNUAL_GROWTH_RATE and NetWorthHistoryService.BIRTH_DATE
public enum ProjectionConfig {
    public static let annualReturn = 0.07
    public static let maxMonths = 1200
    public static let milestones: [Double] = [100_000, 250_000, 500_000, 750_000, 1_000_000]
    public static let birthDate = DateComponents(year: 1996, month: 6, day: 14)
}

public enum Reach: Equatable {
    case reached(age: Int)
    case inMonths(Int, age: Int)
    case never

    /// Japanese wording: "達成 (30歳)", "4年7か月 (34歳)", "100年以上".
    public func text() -> String {
        switch self {
        case .reached(let age): return "達成 (\(age)歳)"
        case .inMonths(let months, let age): return "\(months / 12)年\(months % 12)か月 (\(age)歳)"
        case .never: return "100年以上"
        }
    }
}

/// Pure port of `timeToReachPortfolio` (dashboard.component.ts).
public enum Projection {
    /// 0 if `current >= target`; nil if not reached within `maxMonths` (default `ProjectionConfig.maxMonths`).
    public static func monthsToReach(
        current: Double, target: Double, monthlyContribution: Double, maxMonths: Int = ProjectionConfig.maxMonths
    ) -> Int? {
        if current >= target { return 0 }
        let growth = pow(1 + ProjectionConfig.annualReturn, 1.0 / 12)
        var balance = current
        var months = 0
        while balance < target && months < maxMonths {
            balance = balance * growth + monthlyContribution
            months += 1
        }
        return balance >= target ? months : nil
    }

    public static func reach(
        current: Double, target: Double, monthlyContribution: Double, today: Date, calendar: Calendar
    ) -> Reach {
        guard let months = monthsToReach(current: current, target: target, monthlyContribution: monthlyContribution) else {
            return .never
        }
        let age = ageAfterMonths(today: today, months: months, calendar: calendar)
        return months == 0 ? .reached(age: age) : .inMonths(months, age: age)
    }

    /// Whole years between the birth date and `today + months`. Month-end clamps (31 Jan + 1 month = 28 Feb) match java.time.
    public static func ageAfterMonths(today: Date, months: Int, calendar: Calendar) -> Int {
        let birth = calendar.date(from: ProjectionConfig.birthDate)!
        let later = calendar.date(byAdding: .month, value: months, to: today)!
        return calendar.dateComponents([.year], from: birth, to: later).year ?? 0
    }
}
```

- [ ] **Step 4: Implement `GoalProgress.swift`**

```swift
import Foundation

/// How far along the way to a target you are, in two ways (both on the same constant-return assumption as the milestones):
///  - `growthShare`: the share of the target that will have come from the money you already hold, once it has grown until
///    the target is reached: `P(1+r)^n / T`.
///  - `timeShare`: the share of the whole journey from zero (same monthly contribution and return) that is already behind
///    you: `(n0 - n) / n0`. nil when it is undefined (no monthly contribution).
/// `monthsRemaining` is `n`, the (fractional) months still to go; nil when the target can never be reached.
public struct GoalProgress: Equatable {
    public let monthsRemaining: Double?
    public let growthShare: Double
    public let timeShare: Double?

    public init(monthsRemaining: Double?, growthShare: Double, timeShare: Double?) {
        self.monthsRemaining = monthsRemaining
        self.growthShare = growthShare
        self.timeShare = timeShare
    }

    /// `p` current savings, `t` target, `c` monthly contribution (end of month), `annualReturn` as a decimal.
    public static func compute(p: Double, t: Double, c: Double, annualReturn: Double) -> GoalProgress {
        if p >= t { return GoalProgress(monthsRemaining: 0, growthShare: 1, timeShare: 1) }
        let r = pow(1 + annualReturn, 1.0 / 12) - 1

        if r <= 0 {                                             // no growth: contributions are the only driver
            if c <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: clamp(p / t), timeShare: nil) }
            let share = clamp(p / t)
            return GoalProgress(monthsRemaining: (t - p) / c, growthShare: share, timeShare: share)
        }

        let logGrowth = log(1 + r)
        if c <= 0 {                                             // no contributions: it is all your existing money
            if p <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: 0, timeShare: nil) }
            return GoalProgress(monthsRemaining: log(t / p) / logGrowth, growthShare: 1, timeShare: nil)
        }

        let k = c / r
        if p + k <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: 0, timeShare: 0) }   // contributions never catch up
        let n = log((t + k) / (p + k)) / logGrowth
        let n0 = log(1 + t * r / c) / logGrowth
        let growth = p > 0 ? clamp(p * pow(1 + r, n) / t) : 0
        return GoalProgress(monthsRemaining: n, growthShare: growth, timeShare: clamp((n0 - n) / n0))
    }

    private static func clamp(_ x: Double) -> Double { min(max(x, 0), 1) }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 27 tests, with 0 failures`.

- [ ] **Step 6: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/Projection.swift net-worth-macos/Sources/NetWorthCore/GoalProgress.swift net-worth-macos/Tests/NetWorthCoreTests
git commit -m "macos: port projection maths and the two goal-progress measures"
```

---

### Task 4: Models, settings and URL policy

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/Models.swift`, `net-worth-macos/Sources/NetWorthCore/Settings.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/ModelsTests.swift`, `SettingsTests.swift`

**Interfaces:**
- Produces: `Snapshot`, `HistoryResponse`, `CacheSnapshot`, `WidgetConfig(withContributions:)` (all `Codable`/`Equatable` except `WidgetConfig`); `AppSettings(baseUrl:apiKey:webUrl:)` with `isConfigured` and `AppSettings.defaultBaseUrl`; `SyncStatus(lastSuccessAt:lastError:keyRejected:)`; `UrlPolicy.parse(_:) -> URL?`; `Field`, `Validation`, `SettingsValidator.validate(baseUrl:apiKey:webUrl:) -> Validation`.

- [ ] **Step 1: Write the failing tests**

`Tests/NetWorthCoreTests/ModelsTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class ModelsTests: XCTestCase {
    /// Shape of the real /api/v1/history response, including fields the widget ignores.
    private let realistic = """
    {"snapshots":[
      {"id":1,"timestamp":"2026-09-28T07:00:00Z","currency":"GBP","t212Total":100.5,"cryptoTotal":1.0,
       "chaseTotal":null,"amexDebtTotal":null,"amountLeft":null,"grandTotal":183000.5,
       "grandTotalEur":null,"aiCommentary":"Spending is up"},
      {"id":2,"timestamp":"2026-09-29T07:00:36.680923Z","currency":"GBP","grandTotal":184320.25,
       "someFutureField":{"a":1}}],
     "expectedNetWorthAt40":900000.0,"expectedNetWorthAt50":1500000.0,
     "expectedNetWorthAt60":2500000.0,"expectedNetWorthAt65":3500000.0,
     "expectedNetWorthWithContributionsAt40":1000000.0,"expectedNetWorthWithContributionsAt50":1700000.0,
     "expectedNetWorthWithContributionsAt60":2900000.0,"expectedNetWorthWithContributionsAt65":4100000.0,
     "estimatedNetWorth1y":200000.0,"monthlyContribution":1000.0}
    """

    private func decode(_ json: String) throws -> HistoryResponse {
        try JSONDecoder().decode(HistoryResponse.self, from: Data(json.utf8))
    }

    func testDecodesRealisticResponseAndIgnoresUnknownKeys() throws {
        let h = try decode(realistic)
        XCTAssertEqual(h.snapshots.count, 2)
        XCTAssertEqual(h.snapshots.last!.grandTotal, 184320.25)
        XCTAssertEqual(h.snapshots.last!.currency, "GBP")
        XCTAssertEqual(h.snapshots.last!.timestamp, "2026-09-29T07:00:36.680923Z")
        XCTAssertEqual(h.expectedNetWorthAt40, 900000)
        XCTAssertEqual(h.expectedNetWorthWithContributionsAt65, 4100000)
        XCTAssertEqual(h.estimatedNetWorth1y, 200000)
        XCTAssertEqual(h.monthlyContribution, 1000)
    }

    func testNullOrMissingProjectionFieldsDecodeAsNil() throws {
        let h = try decode(#"{"snapshots":[{"grandTotal":1.0}],"estimatedNetWorth1y":null,"monthlyContribution":null}"#)
        XCTAssertNil(h.expectedNetWorthAt40)
        XCTAssertNil(h.expectedNetWorthWithContributionsAt65)
        XCTAssertNil(h.estimatedNetWorth1y)
        XCTAssertNil(h.monthlyContribution)
        XCTAssertEqual(h.snapshots.first!.currency, "GBP")     // a missing currency falls back to the default
    }

    func testBrandNewDatabaseDecodes() throws {
        XCTAssertTrue(try decode(#"{"snapshots":[]}"#).snapshots.isEmpty)
    }

    func testAJsonBodyWithoutSnapshotsIsNotAHistoryResponse() {
        // {} or {"status":"ok"} means the URL points at some other service; it must not decode as an empty history.
        XCTAssertThrowsError(try decode("{}"))
        XCTAssertThrowsError(try decode(#"{"status":"ok"}"#))
    }

    func testCacheSnapshotRoundTrips() throws {
        let original = CacheSnapshot(
            fetchedAt: 1_790_000_000_000,
            history: HistoryResponse(
                snapshots: [Snapshot(id: 2, timestamp: "t", currency: "EUR", grandTotal: 5.5)], estimatedNetWorth1y: 9))
        let data = try JSONEncoder().encode(original)
        XCTAssertEqual(try JSONDecoder().decode(CacheSnapshot.self, from: data), original)
    }

    func testEmptyCacheHasNoHistory() {
        let c = CacheSnapshot()
        XCTAssertEqual(c.fetchedAt, 0)
        XCTAssertNil(c.history)
    }
}
```

`Tests/NetWorthCoreTests/SettingsTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class UrlPolicyTests: XCTestCase {
    func testAddsHttpsWhenSchemeIsMissing() { XCTAssertEqual(UrlPolicy.parse("nwapi.isym.uk")?.absoluteString, "https://nwapi.isym.uk/") }
    func testTrimsWhitespace() { XCTAssertEqual(UrlPolicy.parse("  https://nwapi.isym.uk/\n")?.absoluteString, "https://nwapi.isym.uk/") }
    func testKeepsAPathPrefix() { XCTAssertEqual(UrlPolicy.parse("https://example.com/nw/")?.absoluteString, "https://example.com/nw/") }
    func testRejectsPlainHttpToARealHost() { XCTAssertNil(UrlPolicy.parse("http://nwapi.isym.uk")) }

    func testAllowsPlainHttpToLocalDevHosts() {
        XCTAssertEqual(UrlPolicy.parse("http://10.0.2.2:8080")?.absoluteString, "http://10.0.2.2:8080/")
        XCTAssertEqual(UrlPolicy.parse("http://localhost:8080")?.absoluteString, "http://localhost:8080/")
    }

    func testRejectsBlankAndGarbage() {
        XCTAssertNil(UrlPolicy.parse(""))
        XCTAssertNil(UrlPolicy.parse("   "))
        XCTAssertNil(UrlPolicy.parse("https://"))
        XCTAssertNil(UrlPolicy.parse("ftp://example.com"))
    }
}

final class SettingsValidatorTests: XCTestCase {
    private func valid(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = "") -> AppSettings {
        guard case .valid(let s) = SettingsValidator.validate(baseUrl: base, apiKey: key, webUrl: web) else {
            XCTFail("expected valid"); return AppSettings()
        }
        return s
    }

    private func errors(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = "") -> Set<Field> {
        guard case .invalid(let e) = SettingsValidator.validate(baseUrl: base, apiKey: key, webUrl: web) else {
            XCTFail("expected invalid"); return []
        }
        return Set(e.keys)
    }

    func testAcceptsAValidTriple() {
        let s = valid(web: "https://net-worth.example.com")
        XCTAssertEqual(s.baseUrl, "https://nwapi.isym.uk/")
        XCTAssertEqual(s.apiKey, "abc123")
        XCTAssertEqual(s.webUrl, "https://net-worth.example.com/")
        XCTAssertTrue(s.isConfigured)
    }

    func testTrimsAPastedKeyWithATrailingNewline() { XCTAssertEqual(valid(key: "  abc123\n").apiKey, "abc123") }
    func testRejectsInnerWhitespaceInTheKey() { XCTAssertEqual(errors(key: "abc 123"), [.apiKey]) }
    func testRejectsAnEmptyKey() { XCTAssertEqual(errors(key: "  "), [.apiKey]) }
    func testNormalisesAMissingScheme() { XCTAssertEqual(valid(base: "nwapi.isym.uk").baseUrl, "https://nwapi.isym.uk/") }
    func testRejectsPlainHttpBaseUrl() { XCTAssertEqual(errors(base: "http://nwapi.isym.uk"), [.baseUrl]) }
    func testRejectsABlankBaseUrl() { XCTAssertEqual(errors(base: ""), [.baseUrl]) }
    func testWebUrlIsOptional() { XCTAssertEqual(valid(web: "  ").webUrl, "") }
    func testRejectsABadWebUrl() { XCTAssertEqual(errors(web: "http://insecure.example.com"), [.webUrl]) }
    func testReportsEveryBadFieldAtOnce() { XCTAssertEqual(errors(base: "", key: "", web: "http://x.com"), [.baseUrl, .apiKey, .webUrl]) }

    func testIsConfiguredNeedsBothUrlAndKey() {
        XCTAssertFalse(AppSettings(baseUrl: "https://x/", apiKey: "").isConfigured)
        XCTAssertFalse(AppSettings(baseUrl: "", apiKey: "k").isConfigured)
        XCTAssertTrue(AppSettings(baseUrl: "https://x/", apiKey: "k").isConfigured)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:" | head -5`
Expected: FAIL with `cannot find 'HistoryResponse' in scope` / `cannot find 'UrlPolicy' in scope`.

- [ ] **Step 3: Implement `Models.swift`**

```swift
import Foundation

/// Lenient on input (unknown keys ignored, null or missing for a defaulted field -> default), omits nils on output.
public struct Snapshot: Codable, Equatable {
    public var id: Int64
    public var timestamp: String
    public var currency: String
    public var grandTotal: Double

    public init(id: Int64 = 0, timestamp: String = "", currency: String = "GBP", grandTotal: Double = 0) {
        self.id = id
        self.timestamp = timestamp
        self.currency = currency
        self.grandTotal = grandTotal
    }

    private enum CodingKeys: String, CodingKey { case id, timestamp, currency, grandTotal }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        timestamp = try c.decodeIfPresent(String.self, forKey: .timestamp) ?? ""
        currency = try c.decodeIfPresent(String.self, forKey: .currency) ?? "GBP"
        grandTotal = try c.decodeIfPresent(Double.self, forKey: .grandTotal) ?? 0
    }
}

/// Mirrors `NetWorthHistoryResponse` (net-worth-service). Every projection is optional; `snapshots` is required so a
/// non-history JSON body fails to decode.
public struct HistoryResponse: Codable, Equatable {
    public var snapshots: [Snapshot]                     // oldest first
    public var expectedNetWorthAt40: Double?
    public var expectedNetWorthAt50: Double?
    public var expectedNetWorthAt60: Double?
    public var expectedNetWorthAt65: Double?
    public var expectedNetWorthWithContributionsAt40: Double?
    public var expectedNetWorthWithContributionsAt50: Double?
    public var expectedNetWorthWithContributionsAt60: Double?
    public var expectedNetWorthWithContributionsAt65: Double?
    public var estimatedNetWorth1y: Double?
    public var monthlyContribution: Double?

    public init(
        snapshots: [Snapshot],
        expectedNetWorthAt40: Double? = nil, expectedNetWorthAt50: Double? = nil,
        expectedNetWorthAt60: Double? = nil, expectedNetWorthAt65: Double? = nil,
        expectedNetWorthWithContributionsAt40: Double? = nil, expectedNetWorthWithContributionsAt50: Double? = nil,
        expectedNetWorthWithContributionsAt60: Double? = nil, expectedNetWorthWithContributionsAt65: Double? = nil,
        estimatedNetWorth1y: Double? = nil, monthlyContribution: Double? = nil
    ) {
        self.snapshots = snapshots
        self.expectedNetWorthAt40 = expectedNetWorthAt40
        self.expectedNetWorthAt50 = expectedNetWorthAt50
        self.expectedNetWorthAt60 = expectedNetWorthAt60
        self.expectedNetWorthAt65 = expectedNetWorthAt65
        self.expectedNetWorthWithContributionsAt40 = expectedNetWorthWithContributionsAt40
        self.expectedNetWorthWithContributionsAt50 = expectedNetWorthWithContributionsAt50
        self.expectedNetWorthWithContributionsAt60 = expectedNetWorthWithContributionsAt60
        self.expectedNetWorthWithContributionsAt65 = expectedNetWorthWithContributionsAt65
        self.estimatedNetWorth1y = estimatedNetWorth1y
        self.monthlyContribution = monthlyContribution
    }
}

/// What the widget renders from; persisted by `HistoryCache`. `fetchedAt` is epoch milliseconds (0 = never).
public struct CacheSnapshot: Codable, Equatable {
    public var fetchedAt: Int64
    public var history: HistoryResponse?

    public init(fetchedAt: Int64 = 0, history: HistoryResponse? = nil) {
        self.fetchedAt = fetchedAt
        self.history = history
    }

    private enum CodingKeys: String, CodingKey { case fetchedAt, history }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        fetchedAt = try c.decodeIfPresent(Int64.self, forKey: .fetchedAt) ?? 0
        history = try c.decodeIfPresent(HistoryResponse.self, forKey: .history)
    }
}

/// Per-widget options (the "with contributions" App Intent setting).
public struct WidgetConfig: Equatable {
    public var withContributions: Bool
    public init(withContributions: Bool = true) { self.withContributions = withContributions }
}
```

- [ ] **Step 4: Implement `Settings.swift`**

```swift
import Foundation

public struct AppSettings: Equatable {
    public var baseUrl: String
    public var apiKey: String
    public var webUrl: String

    public static let defaultBaseUrl = "https://nwapi.isym.uk"

    public init(baseUrl: String = "", apiKey: String = "", webUrl: String = "") {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
        self.webUrl = webUrl
    }

    public var isConfigured: Bool {
        !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Outcome of the last sync attempt, for the widget's hint line and the settings window.
public struct SyncStatus: Equatable {
    public var lastSuccessAt: Int64
    public var lastError: String?
    public var keyRejected: Bool

    public init(lastSuccessAt: Int64 = 0, lastError: String? = nil, keyRejected: Bool = false) {
        self.lastSuccessAt = lastSuccessAt
        self.lastError = lastError
        self.keyRejected = keyRejected
    }
}

public enum UrlPolicy {
    private static let localHosts: Set<String> = ["localhost", "127.0.0.1", "10.0.2.2"]

    /// Normalises a user-typed URL. HTTPS is required except for local dev hosts. The result always has a path ("https://x/").
    public static func parse(_ input: String) -> URL? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.contains("://") { s = "https://" + s }
        guard var comps = URLComponents(string: s),
              let scheme = comps.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = comps.host, !host.isEmpty else { return nil }
        if scheme == "http" && !localHosts.contains(host.lowercased()) { return nil }
        if comps.path.isEmpty { comps.path = "/" }
        return comps.url
    }
}

public enum Field: Hashable { case baseUrl, apiKey, webUrl }

public enum Validation: Equatable {
    case valid(AppSettings)
    case invalid([Field: String])
}

public enum SettingsValidator {
    public static func validate(baseUrl: String, apiKey: String, webUrl: String) -> Validation {
        var errors: [Field: String] = [:]

        let base = UrlPolicy.parse(baseUrl)
        if base == nil { errors[.baseUrl] = "Enter a valid address. https is required (for example https://nwapi.example.com)." }

        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty {
            errors[.apiKey] = "Paste the dashboard access key."
        } else if key.contains(where: { $0.isWhitespace }) {
            errors[.apiKey] = "The key must not contain spaces."
        }

        let web = webUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let webParsed = web.isEmpty ? nil : UrlPolicy.parse(web)
        if !web.isEmpty && webParsed == nil { errors[.webUrl] = "Enter a valid https address, or leave blank." }

        guard errors.isEmpty, let base else { return .invalid(errors) }
        return .valid(AppSettings(baseUrl: base.absoluteString, apiKey: key, webUrl: webParsed?.absoluteString ?? ""))
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 50 tests, with 0 failures`.

- [ ] **Step 6: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/Models.swift net-worth-macos/Sources/NetWorthCore/Settings.swift net-worth-macos/Tests/NetWorthCoreTests
git commit -m "macos: add API models, settings validation and URL policy"
```

---

### Task 5: WidgetModel

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/WidgetModel.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/WidgetModelTests.swift`

**Interfaces:**
- Consumes: everything from Tasks 2-4 and the `T` helpers.
- Produces: `WidgetState` (`.notConfigured`, `.empty(String)`, `.ready(WidgetState.Ready)`), `WidgetState.Ready` (fields: `headlineAmount, headlineUnit, change, changeUp, updated, hint, oneYear, assumption, milestones, projections, projectionTable, nextGoal, periodChanges, peak, trend, trendSpan`; computed `headline`; `nextMilestones(_:)`), row types `MilestoneRow`, `ProjectionRow`, `ProjectionCompareRow`, `PeriodChange`, `TrendPoint(date:total:)`, `GoalBar(fraction:percent:)`, `NextGoal(label:remaining:growth:time:)`; `WidgetModel.build(cache:config:settings:status:now:calendar:) -> WidgetState`, `WidgetModel.staleAfterMs`, `WidgetModel.trendWindowDays`, `WidgetModel.trendMaxPoints`, internal `WidgetModel.parseInstant(_:) -> Date?`.

- [ ] **Step 1: Write the failing tests**

```swift
import XCTest
@testable import NetWorthCore

final class WidgetModelTests: XCTestCase {
    // "Now" is 2026-09-30 12:00 UTC, matching ProjectionTests.
    private let now = ISO8601DateFormatter().date(from: "2026-09-30T12:00:00Z")!
    private var nowMs: Int64 { Int64(now.timeIntervalSince1970 * 1000) }
    private let hour: Int64 = 60 * 60 * 1000

    private let configured = AppSettings(baseUrl: "https://nwapi.isym.uk/", apiKey: "key", webUrl: "")
    private var ok: SyncStatus { SyncStatus(lastSuccessAt: nowMs, lastError: nil, keyRejected: false) }
    private var rejected: SyncStatus { SyncStatus(lastSuccessAt: nowMs - hour, lastError: "Access key rejected", keyRejected: true) }

    private func history(
        _ totals: Double..., currency: String = "GBP", monthly: Double? = 1_000, oneYear: Double? = 210_000,
        at40: Double? = 900_000, at50: Double? = 1_500_000, at60: Double? = 2_500_000, at65: Double? = 3_500_000,
        wc40: Double? = 1_000_000, wc50: Double? = 1_700_000, wc60: Double? = 2_900_000, wc65: Double? = 4_100_000
    ) -> HistoryResponse {
        HistoryResponse(
            snapshots: totals.enumerated().map { i, t in
                Snapshot(id: Int64(i), timestamp: "2026-09-2\(i)T07:00:00Z", currency: currency, grandTotal: t)
            },
            expectedNetWorthAt40: at40, expectedNetWorthAt50: at50, expectedNetWorthAt60: at60, expectedNetWorthAt65: at65,
            expectedNetWorthWithContributionsAt40: wc40, expectedNetWorthWithContributionsAt50: wc50,
            expectedNetWorthWithContributionsAt60: wc60, expectedNetWorthWithContributionsAt65: wc65,
            estimatedNetWorth1y: oneYear, monthlyContribution: monthly)
    }

    /// Snapshots at "days ago" offsets from `now`, oldest first, with the same projections as `history`.
    private func dated(_ points: (Int, Double)...) -> HistoryResponse {
        HistoryResponse(
            snapshots: points.enumerated().map { i, p in
                Snapshot(id: Int64(i), timestamp: T.iso(now.addingTimeInterval(-Double(p.0) * 86_400)), currency: "GBP", grandTotal: p.1)
            },
            estimatedNetWorth1y: 210_000, monthlyContribution: 1_000)
    }

    private func build(
        _ h: HistoryResponse?, config: WidgetConfig = WidgetConfig(), settings: AppSettings? = nil,
        status: SyncStatus? = nil, fetchedAt: Int64? = nil
    ) -> WidgetState {
        WidgetModel.build(
            cache: CacheSnapshot(fetchedAt: fetchedAt ?? nowMs, history: h), config: config,
            settings: settings ?? configured, status: status ?? ok, now: now, calendar: T.calendar)
    }

    private func ready(_ h: HistoryResponse?, config: WidgetConfig = WidgetConfig(), status: SyncStatus? = nil, fetchedAt: Int64? = nil)
        -> WidgetState.Ready {
        guard case .ready(let r) = build(h, config: config, status: status, fetchedAt: fetchedAt) else {
            XCTFail("expected ready"); fatalError()
        }
        return r
    }

    // MARK: timestamps

    func testParsesTimestampsWithAndWithoutFractionalSeconds() {
        XCTAssertNotNil(WidgetModel.parseInstant("2026-08-01T07:00:03.533248Z"))
        XCTAssertNotNil(WidgetModel.parseInstant("2020-07-01T12:00:00Z"))
        XCTAssertNil(WidgetModel.parseInstant("nope"))
    }

    // MARK: empty and unconfigured states

    func testNotConfiguredWhenThereIsNoDataAndNoSettings() {
        XCTAssertEqual(build(nil, settings: AppSettings()), .notConfigured)
    }

    func testWaitingForFirstSyncWhenConfiguredButNothingCached() {
        XCTAssertEqual(build(nil), .empty("初回の同期を待っています"))
    }

    func testARejectedKeyBeforeAnySyncSaysSo() {
        guard case .empty(let m) = build(nil, status: rejected) else { return XCTFail() }
        XCTAssertTrue(m.contains("キーが拒否されました"), m)
    }

    func testABrandNewDatabaseShowsNoDataYet() {
        XCTAssertEqual(build(history()), .empty("データがまだありません"))
    }

    // MARK: headline and change

    func testHeadlineUsesTheLatestSnapshotAndItsCurrency() {
        XCTAssertEqual(ready(history(183_000, 184_320.25)).headline, "184,320ポンド")
        XCTAssertEqual(ready(history(1_000, currency: "EUR")).headline, "1,000ユーロ")
    }

    func testHeadlineIsSplitIntoAmountAndUnit() {
        let s = ready(history(184_320))
        XCTAssertEqual(s.headlineAmount, "184,320")
        XCTAssertEqual(s.headlineUnit, "ポンド")
    }

    func testChangeUpAgainstThePreviousSnapshot() {
        let s = ready(history(184_320, 185_554.4))
        XCTAssertEqual(s.change, "+1,234ポンド (+0.7%)")
        XCTAssertEqual(s.changeUp, true)
    }

    func testChangeDown() {
        let s = ready(history(100_000, 99_500))
        XCTAssertEqual(s.change, "-500ポンド (-0.5%)")
        XCTAssertEqual(s.changeUp, false)
    }

    func testNoChange() {
        let s = ready(history(100_000, 100_000))
        XCTAssertEqual(s.change, "変化なし")
        XCTAssertNil(s.changeUp)
    }

    func testASingleSnapshotHasNoChangeLine() {
        let s = ready(history(50_000))
        XCTAssertNil(s.change)
        XCTAssertNil(s.changeUp)
    }

    func testChangeFromAZeroBaselineOmitsThePercentage() {
        XCTAssertEqual(ready(history(0, 500)).change, "+500ポンド")
    }

    // MARK: milestones

    func testMilestonesWithContributionsMatchTheDashboard() {
        let m = ready(history(183_000, 184_320)).milestones
        XCTAssertEqual(m.map { $0.label }, ["10万ポンド", "25万ポンド", "50万ポンド", "75万ポンド", "100万ポンド"])
        XCTAssertEqual(m[0].value, "達成 (30歳)")
        XCTAssertTrue(m[0].reached)
        XCTAssertEqual(m[1].value, "2年6か月 (32歳)")
        XCTAssertFalse(m[1].reached)
    }

    func testMilestonesWithoutContributionsMatchTheDashboard() {
        let m = ready(history(184_320), config: WidgetConfig(withContributions: false)).milestones
        XCTAssertEqual(m[1].value, "4年7か月 (34歳)")
        XCTAssertEqual(m[4].value, "25年0か月 (55歳)")
    }

    func testAMissingMonthlyContributionActsLikeZero() {
        XCTAssertEqual(ready(history(184_320, monthly: nil)).milestones[1].value, "4年7か月 (34歳)")
    }

    func testNextMilestonesSkipsTheOnesAlreadyReached() {
        XCTAssertEqual(ready(history(184_320)).nextMilestones(3).map { $0.label }, ["25万ポンド", "50万ポンド", "75万ポンド"])
    }

    func testNextMilestonesIsEmptyWhenEverythingIsReached() {
        XCTAssertTrue(ready(history(2_000_000)).nextMilestones(3).isEmpty)
    }

    // MARK: projections and the one-year estimate

    func testProjectionsFollowTheContributionSetting() {
        let h = history(184_320)
        XCTAssertEqual(ready(h).projections.map { "\($0.label)=\($0.value)" },
                       ["40歳=100万ポンド", "50歳=170万ポンド", "60歳=290万ポンド", "65歳=410万ポンド"])
        XCTAssertEqual(ready(h, config: WidgetConfig(withContributions: false)).projections.map { "\($0.label)=\($0.value)" },
                       ["40歳=90万ポンド", "50歳=150万ポンド", "60歳=250万ポンド", "65歳=350万ポンド"])
    }

    func testNilProjectionsAreOmittedAndNeverCrash() {
        let s = ready(history(184_320, oneYear: nil, wc40: nil, wc50: nil, wc60: nil, wc65: nil))
        XCTAssertTrue(s.projections.isEmpty)
        XCTAssertNil(s.oneYear)
        XCTAssertEqual(s.milestones.count, 5)
    }

    func testOneYearEstimateIsCompact() { XCTAssertEqual(ready(history(184_320)).oneYear, "21万ポンド") }

    // MARK: zero, negative, odd currencies

    func testNegativeNetWorthFormatsWithAMinusAndNeverReachesWithoutContributions() {
        let s = ready(history(-1_500), config: WidgetConfig(withContributions: false))
        XCTAssertEqual(s.headline, "-1,500ポンド")
        XCTAssertTrue(s.milestones.allSatisfy { $0.value == "100年以上" && !$0.reached })
    }

    func testZeroNetWorthDoesNotCrash() {
        let s = ready(history(0), config: WidgetConfig(withContributions: false))
        XCTAssertEqual(s.headline, "0ポンド")
        XCTAssertEqual(s.milestones[0].value, "100年以上")
    }

    func testAnUnknownCurrencyUsesItsCode() { XCTAssertEqual(ready(history(1_000, currency: "CHF")).headline, "1,000 CHF") }
    func testABlankCurrencyHasNoUnit() { XCTAssertEqual(ready(history(1_000, currency: "")).headline, "1,000") }

    // MARK: freshness and hints

    func testUpdatedTextIsRelative() {
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 20_000).updated, "たった今更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 5 * 60_000).updated, "5分前に更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 3 * hour).updated, "3時間前に更新")
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs - 49 * hour).updated, "2日前に更新")
    }

    func testAClockSkewedFutureFetchTimeReadsAsJustNow() {
        XCTAssertEqual(ready(history(1), fetchedAt: nowMs + hour).updated, "たった今更新")
    }

    func testNoHintWhenFresh() { XCTAssertNil(ready(history(1), fetchedAt: nowMs - 5 * hour).hint) }

    func testStaleDataGetsAnOutOfDateHintButStaysOnScreen() {
        let s = ready(history(184_320), fetchedAt: nowMs - 7 * hour)
        XCTAssertEqual(s.hint, "古いデータ — サーバーに接続できません")
        XCTAssertEqual(s.headline, "184,320ポンド")
    }

    func testARejectedKeyKeepsTheLastDataAndShowsAHint() {
        let s = ready(history(184_320), status: rejected)
        XCTAssertEqual(s.hint, "キーが拒否されました — アプリで更新してください")
        XCTAssertEqual(s.headline, "184,320ポンド")
    }

    // MARK: assumptions line

    func testAssumptionMentionsTheReturnAndTheMonthlyContribution() {
        XCTAssertEqual(ready(history(184_320)).assumption, "年7%運用・毎月1,000ポンド積立")
        XCTAssertEqual(ready(history(184_320), config: WidgetConfig(withContributions: false)).assumption, "年7%運用・積立なし")
        XCTAssertEqual(ready(history(184_320, monthly: nil)).assumption, "年7%運用・積立なし")
    }

    // MARK: next goal (two progress bars)

    func testNextGoalShowsBothProgressBars() {
        let g = ready(history(48_000)).nextGoal!                 // 7% a year, £1,000 a month
        XCTAssertEqual(g.label, "10万ポンド")
        XCTAssertEqual(g.growth.percent, "59.1%")
        XCTAssertEqual(g.growth.fraction, 0.591, accuracy: 0.001)
        XCTAssertEqual(g.time!.percent, "53.6%")
        XCTAssertEqual(g.remaining, "あと3年1か月")                // 37 months, the same figure as the milestone rows
    }

    func testNextGoalProgressDoesNotDependOnHowLongYouHaveBeenTracking() {
        XCTAssertEqual(ready(dated((3, 40_000), (0, 48_000))).nextGoal, ready(dated((2_280, 1_900), (0, 48_000))).nextGoal)
    }

    func testWithoutContributionsTimeProgressIsNotAvailable() {
        let g = ready(history(48_000), config: WidgetConfig(withContributions: false)).nextGoal!
        XCTAssertEqual(g.growth.percent, "100.0%")
        XCTAssertNil(g.time)
    }

    func testNextGoalWithNoWayToReachItSaysSo() {
        let g = ready(history(0), config: WidgetConfig(withContributions: false)).nextGoal!
        XCTAssertEqual(g.growth.percent, "0.0%")
        XCTAssertEqual(g.remaining, "あと100年以上")
    }

    func testNextGoalSkipsReachedMilestonesAndIsNilWhenAllAreReached() {
        XCTAssertEqual(ready(history(184_320)).nextGoal!.label, "25万ポンド")
        XCTAssertNil(ready(history(2_000_000)).nextGoal)
    }

    // MARK: period changes, peak, trend

    func testPeriodChangesCompareAgainstTheLatestSnapshotAtOrBeforeEachCutoff() {
        let s = ready(dated((40, 40_000), (30, 44_000), (8, 46_000), (0, 48_000)))
        XCTAssertEqual(s.periodChanges.map { $0.label }, ["7日間", "30日間", "全期間"])
        XCTAssertEqual(s.periodChanges[0].text, "+2,000ポンド (+4.3%)")      // 8 days ago: 46,000
        XCTAssertEqual(s.periodChanges[1].text, "+4,000ポンド (+9.1%)")      // 30 days ago: 44,000
        XCTAssertEqual(s.periodChanges[2].text, "+8,000ポンド (+20.0%)")     // first snapshot: 40,000
        XCTAssertTrue(s.periodChanges.allSatisfy { $0.up == true })
    }

    func testPeriodsLongerThanTheHistoryAreOmitted() {
        let s = ready(dated((3, 100), (0, 90)))
        XCTAssertEqual(s.periodChanges.map { $0.label }, ["全期間"])
        XCTAssertEqual(s.periodChanges[0].up, false)
    }

    func testASingleSnapshotHasNoPeriodChangesAndUnparseableTimestampsAreIgnored() {
        XCTAssertTrue(ready(dated((0, 100))).periodChanges.isEmpty)
        let bad = HistoryResponse(snapshots: [Snapshot(id: 1, timestamp: "nope", currency: "GBP", grandTotal: 1),
                                              Snapshot(id: 2, timestamp: "nah", currency: "GBP", grandTotal: 2)])
        let s = ready(bad)
        XCTAssertTrue(s.periodChanges.isEmpty)
        XCTAssertTrue(s.trend.isEmpty)
        XCTAssertEqual(s.trendSpan, "")
    }

    func testPeakIsTheHighestSnapshotEver() {
        XCTAssertEqual(ready(dated((9, 40_000), (5, 50_000), (0, 48_000))).peak, "最高値 50,000ポンド")
    }

    func testTrendKeepsTheLastTwoYearsOldestFirstWithItsSpan() {
        let s = ready(dated((900, 1), (400, 2), (5, 3), (0, 4)))
        XCTAssertEqual(s.trend.map { $0.total }, [2, 3, 4])               // the 900-day-old snapshot is outside the window
        XCTAssertTrue(zip(s.trend, s.trend.dropFirst()).allSatisfy { $0.date < $1.date })
        XCTAssertEqual(s.trendSpan, "13か月間")                            // 400 days ago to today
    }

    func testTrendIsThinnedEvenlyKeepingTheFirstAndLastPoint() {
        let s = ready(dated(contentsOf: (0...599).map { (599 - $0, Double($0)) }))
        XCTAssertEqual(s.trend.count, WidgetModel.trendMaxPoints)
        XCTAssertEqual(s.trend.first!.total, 0)
        XCTAssertEqual(s.trend.last!.total, 599)
    }

    func testTrendSpanIsWrittenInDaysMonthsOrYears() {
        XCTAssertEqual(ready(dated((12, 1), (0, 2))).trendSpan, "12日間")
        XCTAssertEqual(ready(dated((152, 1), (0, 2))).trendSpan, "5か月間")
        XCTAssertEqual(ready(dated((730, 1), (0, 2))).trendSpan, "2年間")
    }

    func testProjectionTableShowsBothScenarios() {
        let t = ready(history(184_320)).projectionTable
        XCTAssertEqual(t.map { $0.label }, ["40歳", "50歳", "60歳", "65歳"])
        XCTAssertEqual(t[0].withContributions, "100万ポンド")
        XCTAssertEqual(t[0].withoutContributions, "90万ポンド")
    }

    func testProjectionTableUsesADashForAMissingScenario() {
        let t = ready(history(184_320, wc40: nil)).projectionTable
        XCTAssertEqual(t[0].withContributions, "-")
        XCTAssertEqual(t[0].withoutContributions, "90万ポンド")
    }
}

private extension WidgetModelTests {
    func dated(contentsOf points: [(Int, Double)]) -> HistoryResponse {
        HistoryResponse(
            snapshots: points.enumerated().map { i, p in
                Snapshot(id: Int64(i), timestamp: T.iso(now.addingTimeInterval(-Double(p.0) * 86_400)), currency: "GBP", grandTotal: p.1)
            },
            estimatedNetWorth1y: 210_000, monthlyContribution: 1_000)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:" | head -5`
Expected: FAIL with `cannot find 'WidgetModel' in scope`.

- [ ] **Step 3: Implement `WidgetModel.swift`**

```swift
import Foundation

public struct MilestoneRow: Equatable { public let label: String; public let value: String; public let reached: Bool }
public struct ProjectionRow: Equatable { public let label: String; public let value: String }
public struct ProjectionCompareRow: Equatable {
    public let label: String; public let withContributions: String; public let withoutContributions: String
}
public struct PeriodChange: Equatable { public let label: String; public let text: String; public let up: Bool? }
public struct TrendPoint: Equatable { public let date: Date; public let total: Double }
public struct GoalBar: Equatable { public let fraction: Double; public let percent: String }
public struct NextGoal: Equatable {
    public let label: String       // "10万ポンド"
    public let remaining: String   // "あと3年1か月"
    public let growth: GoalBar     // share of the target that comes from money you already hold (資産の寄与)
    public let time: GoalBar?      // share of the journey from zero already behind you (時間の進捗); nil when undefined
}

public enum WidgetState: Equatable {
    case notConfigured
    case empty(String)
    case ready(Ready)

    public struct Ready: Equatable {
        public let headlineAmount: String            // "184,320"
        public let headlineUnit: String              // "ポンド" (drawn smaller, next to the amount)
        public let change: String?                   // "+1,234ポンド (+0.7%)" / "変化なし" / nil when there is no previous snapshot
        public let changeUp: Bool?                   // true = up, false = down, nil = neutral
        public let updated: String                   // "3時間前に更新"
        public let hint: String?                     // "キーが拒否されました — …" / "古いデータ — …" / nil
        public let oneYear: String?                  // "21万ポンド"
        public let assumption: String                // "年7%運用・毎月1,000ポンド積立"
        public let milestones: [MilestoneRow]        // all five, in order
        public let projections: [ProjectionRow]
        public let projectionTable: [ProjectionCompareRow]
        public let nextGoal: NextGoal?               // nil when every milestone is reached
        public let periodChanges: [PeriodChange]     // 7日間 / 30日間 / 全期間, where history allows
        public let peak: String?                     // "最高値 49,153ポンド"
        public let trend: [TrendPoint]               // totals over the last two years, oldest first
        public let trendSpan: String                 // "2年間", "5か月間", "12日間"

        public var headline: String { headlineAmount + headlineUnit }

        /// The next `n` milestones that are not yet reached (for the medium layout).
        public func nextMilestones(_ n: Int) -> [MilestoneRow] { Array(milestones.filter { !$0.reached }.prefix(n)) }
    }
}

/// Pure: (cache, config, settings, sync status, now, calendar) -> what to draw. No UI, no I/O.
public enum WidgetModel {
    public static let staleAfterMs: Int64 = 6 * 60 * 60 * 1000
    public static let trendWindowDays = 730     // the chart covers the last two years...
    public static let trendMaxPoints = 200      // ...thinned to this many points

    public static func build(
        cache: CacheSnapshot, config: WidgetConfig, settings: AppSettings, status: SyncStatus, now: Date, calendar: Calendar
    ) -> WidgetState {
        guard let history = cache.history else {
            if !settings.isConfigured { return .notConfigured }
            if status.keyRejected { return .empty("キーが拒否されました — アプリを開いて修正してください") }
            return .empty("初回の同期を待っています")
        }
        guard let latest = history.snapshots.last else { return .empty("データがまだありません") }
        let previous = history.snapshots.count >= 2 ? history.snapshots[history.snapshots.count - 2] : nil
        let unit = Money.unit(latest.currency)
        let today = calendar.startOfDay(for: now)

        let (change, up) = changeLine(
            delta: previous.map { latest.grandTotal - $0.grandTotal }, previousTotal: previous?.grandTotal, unit: unit)

        let contribution = config.withContributions ? (history.monthlyContribution ?? 0) : 0
        let milestones = ProjectionConfig.milestones.map { target -> MilestoneRow in
            let reach = Projection.reach(
                current: latest.grandTotal, target: target, monthlyContribution: contribution, today: today, calendar: calendar)
            let reached: Bool
            if case .reached = reach { reached = true } else { reached = false }
            return MilestoneRow(label: Money.compact(target, unit: unit), value: reach.text(), reached: reached)
        }

        let points: [(date: Date, total: Double)] = history.snapshots
            .compactMap { snap in parseInstant(snap.timestamp).map { ($0, snap.grandTotal) } }
            .sorted { $0.0 < $1.0 }
        let trendPoints = trend(of: points)

        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let ageMs = nowMs - cache.fetchedAt
        let hint: String?
        if status.keyRejected {
            hint = "キーが拒否されました — アプリで更新してください"
        } else if cache.fetchedAt > 0 && ageMs > staleAfterMs {
            hint = "古いデータ — サーバーに接続できません"
        } else {
            hint = nil
        }

        let peak = points.max(by: { $0.total < $1.total }).map { "最高値 " + Money.full($0.total, unit: unit) }

        return .ready(.init(
            headlineAmount: Money.number(latest.grandTotal),
            headlineUnit: unit,
            change: change,
            changeUp: up,
            updated: updatedText(ageMs),
            hint: hint,
            oneYear: history.estimatedNetWorth1y.map { Money.compact($0, unit: unit) },
            assumption: assumption(contribution: contribution, unit: unit),
            milestones: milestones,
            projections: projections(history, withContributions: config.withContributions, unit: unit),
            projectionTable: projectionTable(history, unit: unit),
            nextGoal: nextGoal(total: latest.grandTotal, unit: unit, contribution: contribution),
            periodChanges: periodChanges(points, unit: unit),
            peak: peak,
            trend: trendPoints.map { TrendPoint(date: $0.date, total: $0.total) },
            trendSpan: trendPoints.first.map { spanText(days: daysBetween($0.date, trendPoints.last!.date)) } ?? ""
        ))
    }

    // MARK: - pieces

    static func changeLine(delta: Double?, previousTotal: Double?, unit: String) -> (String?, Bool?) {
        guard let delta, let previousTotal else { return (nil, nil) }
        if Money.roundHalfUp(delta) == 0 { return ("変化なし", nil) }
        let pct = previousTotal != 0 ? String(format: " (%+.1f%%)", delta / abs(previousTotal) * 100) : ""
        return ((delta > 0 ? "+" : "-") + Money.full(abs(delta), unit: unit) + pct, delta > 0)
    }

    private static func projections(_ h: HistoryResponse, withContributions: Bool, unit: String) -> [ProjectionRow] {
        let byAge: [(Int, Double?)] = withContributions
            ? [(40, h.expectedNetWorthWithContributionsAt40), (50, h.expectedNetWorthWithContributionsAt50),
               (60, h.expectedNetWorthWithContributionsAt60), (65, h.expectedNetWorthWithContributionsAt65)]
            : [(40, h.expectedNetWorthAt40), (50, h.expectedNetWorthAt50), (60, h.expectedNetWorthAt60), (65, h.expectedNetWorthAt65)]
        return byAge.compactMap { age, value in value.map { ProjectionRow(label: "\(age)歳", value: Money.compact($0, unit: unit)) } }
    }

    private static func projectionTable(_ h: HistoryResponse, unit: String) -> [ProjectionCompareRow] {
        func cell(_ v: Double?) -> String { v.map { Money.compact($0, unit: unit) } ?? "-" }
        let rows: [(Int, Double?, Double?)] = [
            (40, h.expectedNetWorthWithContributionsAt40, h.expectedNetWorthAt40),
            (50, h.expectedNetWorthWithContributionsAt50, h.expectedNetWorthAt50),
            (60, h.expectedNetWorthWithContributionsAt60, h.expectedNetWorthAt60),
            (65, h.expectedNetWorthWithContributionsAt65, h.expectedNetWorthAt65),
        ]
        return rows.filter { $0.1 != nil || $0.2 != nil }
            .map { ProjectionCompareRow(label: "\($0.0)歳", withContributions: cell($0.1), withoutContributions: cell($0.2)) }
    }

    /// Snapshots from the last `trendWindowDays` days before the latest one, evenly thinned to `trendMaxPoints`
    /// (keeps the first and last).
    private static func trend(of points: [(date: Date, total: Double)]) -> [(date: Date, total: Double)] {
        guard let latestAt = points.last?.date else { return [] }
        let cutoff = latestAt.addingTimeInterval(-Double(trendWindowDays) * 86_400)
        let recent = points.filter { $0.date >= cutoff }
        if recent.count <= trendMaxPoints { return recent }
        return (0..<trendMaxPoints).map { i in recent[i * (recent.count - 1) / (trendMaxPoints - 1)] }
    }

    private static func spanText(days: Int) -> String {
        if days < 60 { return "\(days)日間" }
        if days < 700 { return "\(Money.roundHalfUp(Double(days) / 30.4))か月間" }
        return "\(Money.roundHalfUp(Double(days) / 365.25))年間"
    }

    /// Change over the last 7 and 30 days (only when history goes back that far) and since the first snapshot ("全期間").
    private static func periodChanges(_ points: [(date: Date, total: Double)], unit: String) -> [PeriodChange] {
        guard points.count >= 2, let last = points.last, let first = points.first else { return [] }
        var out: [PeriodChange] = []
        for (days, label) in [(7, "7日間"), (30, "30日間")] {
            let cutoff = last.date.addingTimeInterval(-Double(days) * 86_400)
            guard let base = points.last(where: { $0.date <= cutoff }) else { continue }
            let (text, up) = changeLine(delta: last.total - base.total, previousTotal: base.total, unit: unit)
            if let text { out.append(PeriodChange(label: label, text: text, up: up)) }
        }
        let (text, up) = changeLine(delta: last.total - first.total, previousTotal: first.total, unit: unit)
        if let text { out.append(PeriodChange(label: "全期間", text: text, up: up)) }
        return out
    }

    private static func nextGoal(total: Double, unit: String, contribution: Double) -> NextGoal? {
        guard let target = ProjectionConfig.milestones.first(where: { $0 > total }) else { return nil }
        let progress = GoalProgress.compute(p: total, t: target, c: contribution, annualReturn: ProjectionConfig.annualReturn)
        let monthsLeft = Projection.monthsToReach(current: total, target: target, monthlyContribution: contribution)
        let remaining = monthsLeft.map { "あと\($0 / 12)年\($0 % 12)か月" } ?? "あと100年以上"
        return NextGoal(
            label: Money.compact(target, unit: unit), remaining: remaining,
            growth: goalBar(progress.growthShare), time: progress.timeShare.map(goalBar))
    }

    private static func goalBar(_ share: Double) -> GoalBar { GoalBar(fraction: share, percent: String(format: "%.1f%%", share * 100)) }

    private static func assumption(contribution: Double, unit: String) -> String {
        let rate = "年\(Money.roundHalfUp(ProjectionConfig.annualReturn * 100))%運用"
        return contribution > 0 ? "\(rate)・毎月\(Money.full(contribution, unit: unit))積立" : "\(rate)・積立なし"
    }

    private static func updatedText(_ ms: Int64) -> String {
        if ms < 60_000 { return "たった今更新" }                    // also covers a fetch time in the future (clock skew)
        if ms < 3_600_000 { return "\(ms / 60_000)分前に更新" }
        if ms < 86_400_000 { return "\(ms / 3_600_000)時間前に更新" }
        return "\(ms / 86_400_000)日前に更新"
    }

    private static func daysBetween(_ a: Date, _ b: Date) -> Int { Int(b.timeIntervalSince(a) / 86_400) }

    /// Parses "2026-08-01T07:00:03.533248Z" and "2020-07-01T12:00:00Z" (fractional seconds are dropped).
    static func parseInstant(_ s: String) -> Date? {
        var t = s
        if let r = t.range(of: #"\.\d+"#, options: .regularExpression) { t.removeSubrange(r) }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: t)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 94 tests, with 0 failures`.

- [ ] **Step 5: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/WidgetModel.swift net-worth-macos/Tests/NetWorthCoreTests/WidgetModelTests.swift
git commit -m "macos: port the widget model (headline, milestones, goal bars, changes, trend)"
```

---

### Task 6: API client, storage and sync

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/ApiClient.swift`, `Storage.swift`, `Sync.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/ApiClientTests.swift`, `StorageTests.swift`, `SyncTests.swift`

**Interfaces:**
- Consumes: `HistoryResponse`, `CacheSnapshot`, `AppSettings`, `SyncStatus`, `UrlPolicy`.
- Produces: `ApiError` (`notConfigured`, `http(status:detail:)`, `network(String)`, `decoding`, `storage(String)`; `userMessage`, `rejectsKey`); `protocol HistorySource { func history() async throws -> HistoryResponse }`; `ApiClient(baseUrl:apiKey:session:)` and `ApiClient.defaultSession`; `HistoryCache(fileURL:)` with `load()`/`save(_:) throws`; `protocol SecretStore { loadKey(); saveKey(_:) throws }`; `SettingsStore(defaults:secrets:)` with `load()`, `save(_:) throws`, `status()`, `recordSync(_:at:)`, `source(session:) -> HistorySource?`; `KeychainSecretStore(service:accessGroup:)`; `SyncResult` (`.updated`, `.failed(ApiError)`); `Sync(cache:source:clock:onResult:)` with `refresh() async -> SyncResult`.

- [ ] **Step 1: Write the failing tests**

`Tests/NetWorthCoreTests/ApiClientTests.swift` (uses a `URLProtocol` stub, so nothing touches the network):

```swift
import XCTest
@testable import NetWorthCore

/// Answers every request from a closure, so no test touches the network.
final class StubURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let (response, data) = try StubURLProtocol.handler!(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }
}

final class ApiClientTests: XCTestCase {
    private let base = URL(string: "https://nwapi.isym.uk/")!

    private func respond(_ status: Int, _ body: String) {
        StubURLProtocol.handler = { req in
            (HTTPURLResponse(url: req.url!, statusCode: status, httpVersion: nil, headerFields: nil)!, Data(body.utf8))
        }
    }

    private func client(key: String = "secret") -> ApiClient {
        ApiClient(baseUrl: base, apiKey: key, session: StubURLProtocol.session())
    }

    func testSendsTheKeyToTheHistoryEndpointAndDecodesTheBody() async throws {
        var seen: URLRequest?
        StubURLProtocol.handler = { req in
            seen = req
            return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!,
                    Data(#"{"snapshots":[{"id":1,"timestamp":"2026-09-29T07:00:00Z","currency":"GBP","grandTotal":5.5}]}"#.utf8))
        }
        let h = try await client(key: "  secret\n").history()
        XCTAssertEqual(seen?.url?.absoluteString, "https://nwapi.isym.uk/api/v1/history")
        XCTAssertEqual(seen?.value(forHTTPHeaderField: "X-Api-Key"), "secret")
        XCTAssertEqual(h.snapshots.first?.grandTotal, 5.5)
    }

    func testAPathPrefixIsKept() async throws {
        var seen: URL?
        StubURLProtocol.handler = { req in
            seen = req.url
            return (HTTPURLResponse(url: req.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data(#"{"snapshots":[]}"#.utf8))
        }
        _ = try await ApiClient(baseUrl: URL(string: "https://example.com/nw/")!, apiKey: "k", session: StubURLProtocol.session()).history()
        XCTAssertEqual(seen?.absoluteString, "https://example.com/nw/api/v1/history")
    }

    func test401And403RejectTheKey() async {
        for status in [401, 403] {
            respond(status, #"{"error":"Invalid API key"}"#)
            do { _ = try await client().history(); XCTFail() } catch let e as ApiError {
                XCTAssertEqual(e, .http(status: status, detail: "Invalid API key"))
                XCTAssertTrue(e.rejectsKey)
                XCTAssertTrue(e.userMessage.contains("Access key rejected"))
            } catch { XCTFail("\(error)") }
        }
    }

    func testAServerErrorDoesNotRejectTheKey() async {
        respond(500, #"{"message":"boom"}"#)
        do { _ = try await client().history(); XCTFail() } catch let e as ApiError {
            XCTAssertEqual(e, .http(status: 500, detail: "boom"))
            XCTAssertFalse(e.rejectsKey)
            XCTAssertEqual(e.userMessage, "Server error (HTTP 500)")
        } catch { XCTFail("\(error)") }
    }

    func testANetworkFailureIsANetworkError() async {
        StubURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
        do { _ = try await client().history(); XCTFail() } catch let e as ApiError {
            guard case .network = e else { return XCTFail("\(e)") }
            XCTAssertFalse(e.rejectsKey)
        } catch { XCTFail("\(error)") }
    }

    func testGarbageAndNonHistoryBodiesAreDecodingErrors() async {
        for body in ["<html>hi</html>", "{}", #"{"status":"ok"}"#, ""] {
            respond(200, body)
            do { _ = try await client().history(); XCTFail("decoded \(body)") } catch let e as ApiError {
                XCTAssertEqual(e, .decoding)
            } catch { XCTFail("\(error)") }
        }
    }

    func testAKeyWithControlCharactersIsNotConfigured() async {
        respond(200, #"{"snapshots":[]}"#)
        do { _ = try await client(key: "abc\u{7}def").history(); XCTFail() } catch let e as ApiError {
            XCTAssertEqual(e, .notConfigured)
        } catch { XCTFail("\(error)") }
    }
}
```

`Tests/NetWorthCoreTests/StorageTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class InMemorySecrets: SecretStore {
    var key = ""
    func loadKey() -> String { key }
    func saveKey(_ key: String) throws { self.key = key }
}

final class HistoryCacheTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    private func snap(_ total: Double) -> CacheSnapshot {
        CacheSnapshot(fetchedAt: 42, history: HistoryResponse(snapshots: [Snapshot(grandTotal: total)]))
    }

    func testMissingFileLoadsAsEmpty() {
        let c = HistoryCache(fileURL: dir.appendingPathComponent("nope.json")).load()
        XCTAssertEqual(c.fetchedAt, 0)
        XCTAssertNil(c.history)
    }

    func testCorruptFileLoadsAsEmptyInsteadOfThrowing() throws {
        let f = dir.appendingPathComponent("cache.json")
        try "{ this is not json".write(to: f, atomically: true, encoding: .utf8)
        XCTAssertNil(HistoryCache(fileURL: f).load().history)
    }

    func testRoundTrips() throws {
        let cache = HistoryCache(fileURL: dir.appendingPathComponent("cache.json"))
        try cache.save(snap(123))
        XCTAssertEqual(cache.load(), snap(123))
    }

    func testSaveOverwritesAndLeavesNoTempFiles() throws {
        let cache = HistoryCache(fileURL: dir.appendingPathComponent("cache.json"))
        try cache.save(snap(1))
        try cache.save(snap(2))
        XCTAssertEqual(cache.load().history?.snapshots.first?.grandTotal, 2)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["cache.json"])
    }

    func testCreatesMissingParentDirectories() throws {
        let f = dir.appendingPathComponent("a/b/cache.json")
        try HistoryCache(fileURL: f).save(snap(5))
        XCTAssertTrue(FileManager.default.fileExists(atPath: f.path))
    }
}

final class SettingsStoreTests: XCTestCase {
    private var suite = ""
    private var defaults: UserDefaults!
    private let secrets = InMemorySecrets()

    override func setUp() {
        suite = "test.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
    }

    override func tearDown() { defaults.removePersistentDomain(forName: suite) }

    private func store() -> SettingsStore { SettingsStore(defaults: defaults, secrets: secrets) }

    func testSavesAndLoadsSettingsWithTheKeyInTheSecretStore() throws {
        try store().save(AppSettings(baseUrl: "https://x/", apiKey: "k", webUrl: "https://w/"))
        XCTAssertEqual(secrets.key, "k")
        XCTAssertNil(defaults.string(forKey: "api_key"))         // the key never lands in UserDefaults
        XCTAssertEqual(store().load(), AppSettings(baseUrl: "https://x/", apiKey: "k", webUrl: "https://w/"))
    }

    func testStatusStartsEmptyThenRecordsSuccessAndFailure() {
        let s = store()
        XCTAssertEqual(s.status(), SyncStatus())
        s.recordSync(.failed(.http(status: 401, detail: "")), at: 5)
        XCTAssertTrue(s.status().keyRejected)
        XCTAssertNotNil(s.status().lastError)
        s.recordSync(.updated, at: 1_000)
        XCTAssertEqual(s.status(), SyncStatus(lastSuccessAt: 1_000, lastError: nil, keyRejected: false))
        s.recordSync(.failed(.network("x")), at: 2_000)
        XCTAssertFalse(s.status().keyRejected)
        XCTAssertEqual(s.status().lastSuccessAt, 1_000)          // a failure keeps the last success time
    }

    func testSourceIsNilUntilConfiguredWithAValidUrl() throws {
        XCTAssertNil(store().source())
        try store().save(AppSettings(baseUrl: "http://insecure.example.com", apiKey: "k"))
        XCTAssertNil(store().source())
        try store().save(AppSettings(baseUrl: "https://nwapi.isym.uk/", apiKey: "k"))
        XCTAssertNotNil(store().source())
    }
}
```

`Tests/NetWorthCoreTests/SyncTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class SyncTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    private func cache() -> HistoryCache { HistoryCache(fileURL: dir.appendingPathComponent("cache.json")) }

    private struct FakeSource: HistorySource {
        let result: () throws -> HistoryResponse
        func history() async throws -> HistoryResponse { try result() }
    }

    private func history(_ totals: Double...) -> HistoryResponse {
        HistoryResponse(snapshots: totals.enumerated().map { Snapshot(id: Int64($0), grandTotal: $1) }, estimatedNetWorth1y: 9)
    }

    func testSuccessKeepsTheWholeHistoryAndStampsFetchTime() async {
        let c = cache()
        var results: [(SyncResult, Int64)] = []
        let sync = Sync(cache: c, source: { FakeSource { self.history(1, 2, 3, 4) } }, clock: { 777 }, onResult: { results.append(($0, $1)) })

        let r = await sync.refresh()

        XCTAssertEqual(r, .updated)
        let saved = c.load()
        XCTAssertEqual(saved.fetchedAt, 777)
        XCTAssertEqual(saved.history?.snapshots.map { $0.grandTotal }, [1, 2, 3, 4])
        XCTAssertEqual(saved.history?.estimatedNetWorth1y, 9)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].0, .updated)
        XCTAssertEqual(results[0].1, 777)
    }

    func testNotConfiguredFailsWithoutTouchingTheCache() async {
        let c = cache()
        let r = await Sync(cache: c, source: { nil }).refresh()
        XCTAssertEqual(r, .failed(.notConfigured))
        XCTAssertNil(c.load().history)
    }

    func testAFailureLeavesTheCacheAlone() async throws {
        let c = cache()
        try c.save(CacheSnapshot(fetchedAt: 1, history: history(5)))
        for error in [ApiError.network("offline"), .http(status: 401, detail: ""), .decoding] {
            let r = await Sync(cache: c, source: { FakeSource { throw error } }).refresh()
            XCTAssertEqual(r, .failed(error))
            XCTAssertEqual(c.load().history?.snapshots.first?.grandTotal, 5)
            XCTAssertEqual(c.load().fetchedAt, 1)
        }
    }

    func testAnUnwritableCacheIsAStorageError() async throws {
        let blocker = dir.appendingPathComponent("blocker")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try "x".write(to: blocker, atomically: true, encoding: .utf8)        // a file where a directory must be
        let c = HistoryCache(fileURL: blocker.appendingPathComponent("cache.json"))
        let r = await Sync(cache: c, source: { FakeSource { self.history(1) } }).refresh()
        guard case .failed(.storage) = r else { return XCTFail("\(r)") }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:" | head -5`
Expected: FAIL with `cannot find 'ApiClient' in scope` (and `HistoryCache`, `Sync`).

- [ ] **Step 3: Implement `ApiClient.swift`**

```swift
import Foundation

public enum ApiError: Error, Equatable {
    case notConfigured
    case http(status: Int, detail: String)
    case network(String)
    case decoding
    case storage(String)

    public var userMessage: String {
        switch self {
        case .notConfigured: return "Enter the API URL and access key first"
        case .http(let status, _):
            return (status == 401 || status == 403)
                ? "Access key rejected (HTTP \(status)) — check the dashboard access key"
                : "Server error (HTTP \(status))"
        case .network: return "Can't reach the server — check the URL and your connection"
        case .decoding: return "Unexpected response — is this the Net Worth API URL?"
        case .storage: return "Couldn't save data on this Mac — is the disk full?"
        }
    }

    /// True when the server said the key is wrong (as opposed to being down or unreachable).
    public var rejectsKey: Bool {
        if case .http(let status, _) = self { return status == 401 || status == 403 }
        return false
    }
}

/// What `Sync` needs from the network; lets tests substitute a fake.
public protocol HistorySource {
    func history() async throws -> HistoryResponse
}

/// Talks to net-worth-service. Deliberately does no logging: the X-Api-Key header must never reach a log.
public struct ApiClient: HistorySource {
    private let baseUrl: URL
    private let key: String
    private let session: URLSession

    public init(baseUrl: URL, apiKey: String, session: URLSession = ApiClient.defaultSession) {
        self.baseUrl = baseUrl
        self.key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.session = session
    }

    public static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 20
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    public func history() async throws -> HistoryResponse {
        // A pasted key containing control characters can never be a valid header value.
        if key.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7f }) { throw ApiError.notConfigured }
        var request = URLRequest(url: baseUrl.appendingPathComponent("api/v1/history"))
        request.httpMethod = "GET"
        request.setValue(key, forHTTPHeaderField: "X-Api-Key")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw ApiError.network(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw ApiError.decoding }
        guard (200..<300).contains(http.statusCode) else {
            throw ApiError.http(status: http.statusCode, detail: Self.errorDetail(data))
        }
        do {
            return try JSONDecoder().decode(HistoryResponse.self, from: data)
        } catch {
            throw ApiError.decoding
        }
    }

    /// The auth filter answers `{"error": ...}`; Spring's default error body uses `message`.
    static func errorDetail(_ data: Data) -> String {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        return (obj["message"] as? String) ?? (obj["error"] as? String) ?? ""
    }
}
```

- [ ] **Step 4: Implement `Storage.swift`**

```swift
import Foundation
import Security

/// One JSON file holding the last good API response. Writes are atomic (temp file + rename) so the widget never reads a
/// half-written cache, and a missing or corrupt file loads as empty instead of throwing.
public final class HistoryCache {
    private let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    public func load() -> CacheSnapshot {
        guard let data = try? Data(contentsOf: fileURL),
              let snapshot = try? JSONDecoder().decode(CacheSnapshot.self, from: data) else { return CacheSnapshot() }
        return snapshot
    }

    public func save(_ snapshot: CacheSnapshot) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(snapshot).write(to: fileURL, options: .atomic)
    }
}

/// Where the access key lives (the Keychain in the real app; in memory in tests).
public protocol SecretStore {
    func loadKey() -> String
    func saveKey(_ key: String) throws
}

/// URL settings and the last sync status live in shared `UserDefaults` (an App Group suite); the key lives in `SecretStore`.
public final class SettingsStore {
    private let defaults: UserDefaults
    private let secrets: SecretStore

    private enum K {
        static let url = "base_url", web = "web_url", ok = "last_ok", err = "last_err", rejected = "key_rejected"
    }

    public init(defaults: UserDefaults, secrets: SecretStore) {
        self.defaults = defaults
        self.secrets = secrets
    }

    public func load() -> AppSettings {
        AppSettings(baseUrl: defaults.string(forKey: K.url) ?? "", apiKey: secrets.loadKey(), webUrl: defaults.string(forKey: K.web) ?? "")
    }

    public func save(_ s: AppSettings) throws {
        try secrets.saveKey(s.apiKey)
        defaults.set(s.baseUrl, forKey: K.url)
        defaults.set(s.webUrl, forKey: K.web)
    }

    public func status() -> SyncStatus {
        SyncStatus(lastSuccessAt: Int64(defaults.object(forKey: K.ok) as? Int ?? 0),
                   lastError: defaults.string(forKey: K.err), keyRejected: defaults.bool(forKey: K.rejected))
    }

    public func recordSync(_ result: SyncResult, at ms: Int64) {
        switch result {
        case .failed(let error):
            defaults.set(error.userMessage, forKey: K.err)
            defaults.set(error.rejectsKey, forKey: K.rejected)
        case .updated:
            defaults.set(Int(ms), forKey: K.ok)
            defaults.removeObject(forKey: K.err)
            defaults.removeObject(forKey: K.rejected)
        }
    }

    /// A source for the current settings, or nil when not configured / the URL is invalid.
    public func source(session: URLSession = ApiClient.defaultSession) -> HistorySource? {
        let s = load()
        guard s.isConfigured, let url = UrlPolicy.parse(s.baseUrl) else { return nil }
        return ApiClient(baseUrl: url, apiKey: s.apiKey, session: session)
    }
}

/// The key in the data-protection Keychain under a shared access group, so the app and the widget extension both read it.
public final class KeychainSecretStore: SecretStore {
    private let service: String
    private let accessGroup: String?

    public init(service: String, accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query() -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "api_key",
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let accessGroup { q[kSecAttrAccessGroup as String] = accessGroup }
        return q
    }

    public func loadKey() -> String {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    public func saveKey(_ key: String) throws {
        SecItemDelete(query() as CFDictionary)
        if key.isEmpty { return }
        var q = query()
        q[kSecValueData as String] = Data(key.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(q as CFDictionary, nil)
        if status != errSecSuccess { throw ApiError.storage("Keychain error \(status)") }
    }
}
```

- [ ] **Step 5: Implement `Sync.swift`**

```swift
import Foundation

public enum SyncResult: Equatable {
    case updated
    case failed(ApiError)
}

/// Refreshes the cache from the API. On *any* failure the cache is left exactly as it was, so the widget keeps rendering
/// the last good data.
public struct Sync {
    private let cache: HistoryCache
    private let source: () -> HistorySource?
    private let clock: () -> Int64
    private let onResult: (SyncResult, Int64) -> Void

    public init(
        cache: HistoryCache,
        source: @escaping () -> HistorySource?,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        onResult: @escaping (SyncResult, Int64) -> Void = { _, _ in }
    ) {
        self.cache = cache
        self.source = source
        self.clock = clock
        self.onResult = onResult
    }

    @discardableResult
    public func refresh() async -> SyncResult {
        let result = await attempt()
        onResult(result, clock())
        return result
    }

    private func attempt() async -> SyncResult {
        guard let api = source() else { return .failed(.notConfigured) }
        do {
            let fresh = try await api.history()
            // Keep every snapshot: the change lines, all-time high and trend chart need the whole history.
            try cache.save(CacheSnapshot(fetchedAt: clock(), history: fresh))
            return .updated
        } catch let error as ApiError {
            return .failed(error)
        } catch {
            return .failed(.storage(error.localizedDescription))
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 113 tests, with 0 failures`.

- [ ] **Step 7: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/ApiClient.swift net-worth-macos/Sources/NetWorthCore/Storage.swift net-worth-macos/Sources/NetWorthCore/Sync.swift net-worth-macos/Tests/NetWorthCoreTests
git commit -m "macos: add API client, cache/settings storage and sync"
```

---

### Task 7: Shared environment and timeline plan

**Files:**
- Create: `net-worth-macos/Sources/NetWorthCore/SharedEnvironment.swift`, `EntryPlan.swift`
- Test: `net-worth-macos/Tests/NetWorthCoreTests/SharedEnvironmentTests.swift`, `EntryPlanTests.swift`

**Interfaces:**
- Consumes: `SharedIds`, `SettingsStore`, `KeychainSecretStore`, `HistoryCache`, `WidgetModel`.
- Produces: `SharedEnvironment(settings:cache:)` with `SharedEnvironment.make(bundle:)` and `SharedEnvironment.make(appGroup:keychainGroup:)` (falls back to a private temp location when the group is unavailable); `EntryPlan.step`, `EntryPlan.count`, `EntryPlan.refreshAfter`, `EntryPlan.dates(from:) -> [Date]`, `EntryPlan.states(cache:config:settings:status:start:calendar:) -> [(date: Date, state: WidgetState)]`.

- [ ] **Step 1: Write the failing tests**

`Tests/NetWorthCoreTests/EntryPlanTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class EntryPlanTests: XCTestCase {
    private let start = ISO8601DateFormatter().date(from: "2026-09-30T12:00:00Z")!

    func testTwelveEntriesThirtyMinutesApart() {
        let d = EntryPlan.dates(from: start)
        XCTAssertEqual(d.count, 12)
        XCTAssertEqual(d[0], start)
        XCTAssertEqual(d[1].timeIntervalSince(d[0]), 1_800)
        XCTAssertEqual(d[11].timeIntervalSince(start), 11 * 1_800)
    }

    func testUpdatedTextAdvancesAndTheStaleHintAppearsOnItsOwn() {
        let fetchedAt = Int64((start.timeIntervalSince1970 - 5 * 3_600 - 40 * 60) * 1000)      // 5h40m old at the first entry
        let history = HistoryResponse(snapshots: [Snapshot(id: 1, timestamp: "2026-09-30T07:00:00Z", currency: "GBP", grandTotal: 1_000)])
        let states = EntryPlan.states(
            cache: CacheSnapshot(fetchedAt: fetchedAt, history: history), config: WidgetConfig(),
            settings: AppSettings(baseUrl: "https://x/", apiKey: "k"), status: SyncStatus(), start: start, calendar: T.calendar)

        func ready(_ i: Int) -> WidgetState.Ready {
            guard case .ready(let r) = states[i].state else { fatalError() }
            return r
        }
        XCTAssertEqual(ready(0).updated, "5時間前に更新")
        XCTAssertNil(ready(0).hint)
        XCTAssertEqual(ready(1).updated, "6時間前に更新")           // 30 minutes later the data is over 6 hours old
        XCTAssertEqual(ready(1).hint, "古いデータ — サーバーに接続できません")
        XCTAssertEqual(ready(11).updated, "11時間前に更新")
    }
}
```

`Tests/NetWorthCoreTests/SharedEnvironmentTests.swift`:

```swift
import XCTest
@testable import NetWorthCore

final class SharedEnvironmentTests: XCTestCase {
    func testWithoutAnAppGroupItFallsBackAndTheWidgetShowsNotConfigured() {
        let env = SharedEnvironment.make(appGroup: nil, keychainGroup: nil)
        XCTAssertNil(env.cache.load().history)
        let state = WidgetModel.build(
            cache: env.cache.load(), config: WidgetConfig(), settings: AppSettings(), status: SyncStatus(),
            now: Date(), calendar: T.calendar)
        XCTAssertEqual(state, .notConfigured)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:" | head -5`
Expected: FAIL with `cannot find 'EntryPlan' in scope` / `cannot find 'SharedEnvironment' in scope`.

- [ ] **Step 3: Implement `SharedEnvironment.swift`**

```swift
import Foundation

/// The settings store and cache the app and the widget share. If the App Group is unavailable (a signing problem) it falls
/// back to a private temporary location, so the widget renders "not configured" instead of crashing.
public struct SharedEnvironment {
    public let settings: SettingsStore
    public let cache: HistoryCache

    public init(settings: SettingsStore, cache: HistoryCache) {
        self.settings = settings
        self.cache = cache
    }

    public static func make(bundle: Bundle = .main) -> SharedEnvironment {
        make(appGroup: SharedIds.appGroup(bundle), keychainGroup: SharedIds.keychainGroup(bundle))
    }

    public static func make(appGroup: String?, keychainGroup: String?) -> SharedEnvironment {
        let container = appGroup.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
        let defaults = appGroup.flatMap { UserDefaults(suiteName: $0) } ?? UserDefaults(suiteName: "com.isym444.networth.fallback")!
        let dir = container ?? FileManager.default.temporaryDirectory.appendingPathComponent("networth-fallback")
        return SharedEnvironment(
            settings: SettingsStore(defaults: defaults, secrets: KeychainSecretStore(service: SharedIds.keychainService, accessGroup: keychainGroup)),
            cache: HistoryCache(fileURL: dir.appendingPathComponent("cache.json")))
    }
}
```

- [ ] **Step 4: Implement `EntryPlan.swift`**

```swift
import Foundation

/// The widget timeline: one entry every 30 minutes for the next 6 hours, each built from the same cache but with its own
/// "now", so "N分前に更新" advances and the out-of-date hint appears by itself once the data is 6 hours old.
public enum EntryPlan {
    public static let step: TimeInterval = 30 * 60
    public static let count = 12
    /// Ask WidgetKit for a fresh timeline after this long (it budgets refreshes, so the real timing is up to the system).
    public static let refreshAfter: TimeInterval = 60 * 60

    public static func dates(from start: Date) -> [Date] { (0..<count).map { start.addingTimeInterval(Double($0) * step) } }

    public static func states(
        cache: CacheSnapshot, config: WidgetConfig, settings: AppSettings, status: SyncStatus, start: Date, calendar: Calendar
    ) -> [(date: Date, state: WidgetState)] {
        dates(from: start).map {
            ($0, WidgetModel.build(cache: cache, config: config, settings: settings, status: status, now: $0, calendar: calendar))
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 116 tests, with 0 failures`.

- [ ] **Step 6: Commit (once approved)**

```bash
git add net-worth-macos/Sources/NetWorthCore/SharedEnvironment.swift net-worth-macos/Sources/NetWorthCore/EntryPlan.swift net-worth-macos/Tests/NetWorthCoreTests
git commit -m "macos: add the shared environment and the timeline entry plan"
```

---

### Task 8: SwiftUI layouts and the offscreen renderer

The layouts are checked by eye: the renderer draws every size to a PNG using the synthetic `SampleData`, and you read the PNGs with the image-reading tool.

**Files:**
- Modify: `net-worth-macos/Package.swift` (add the Views library and the renderer)
- Modify: `net-worth-macos/project.yml` (widget target depends on `NetWorthViews`)
- Create: `net-worth-macos/Sources/NetWorthViews/Palette.swift`, `Components.swift`, `NetWorthWidgetView.swift`, `SampleData.swift`
- Create: `net-worth-macos/Sources/LayoutRenderer/main.swift`

**Interfaces:**
- Consumes: `WidgetState` and its row types (Task 5).
- Produces: `public enum LayoutKind: CaseIterable { small, medium, large, extraLarge; var size: CGSize }`; `public struct NetWorthWidgetView: View` with `init(state: WidgetState, layout: LayoutKind)` (draws its own 14pt padding; the widget must use `.contentMarginsDisabled()`); `public struct Palette` with `init(_ scheme: ColorScheme)` and `background`; `public enum SampleData { history(now:) , state(now:calendar:contributions:) }`.

- [ ] **Step 1: Replace `Package.swift` with the full version**

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NetWorthMac",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "NetWorthCore", targets: ["NetWorthCore"]),
        .library(name: "NetWorthViews", targets: ["NetWorthViews"]),
    ],
    targets: [
        .target(name: "NetWorthCore"),
        .target(name: "NetWorthViews", dependencies: ["NetWorthCore"]),
        .executableTarget(name: "LayoutRenderer", dependencies: ["NetWorthViews", "NetWorthCore"]),
        .testTarget(name: "NetWorthCoreTests", dependencies: ["NetWorthCore"]),
    ],
    swiftLanguageModes: [.v5]
)
```

- [ ] **Step 2: Add the views**

`Sources/NetWorthViews/Palette.swift`:

```swift
import SwiftUI

/// The widget palette (same values as the Android widget), resolved from the color scheme so it renders identically in
/// the widget and in the offscreen layout renderer.
public struct Palette {
    public let scheme: ColorScheme

    public init(_ scheme: ColorScheme) { self.scheme = scheme }

    private var dark: Bool { scheme == .dark }
    private func hex(_ v: UInt32) -> Color {
        Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    public var background: Color { dark ? hex(0x1C1C1E) : hex(0xFFFFFF) }
    public var primary: Color { dark ? hex(0xF2F2F7) : hex(0x111111) }
    public var secondary: Color { dark ? hex(0x9A9AA0) : hex(0x6B6B70) }
    public var up: Color { dark ? hex(0x66BB6A) : hex(0x2E7D32) }
    public var down: Color { dark ? hex(0xEF5350) : hex(0xC62828) }
    public var warn: Color { hex(0xFB8C00) }
    public var accent: Color { dark ? hex(0x64B5F6) : hex(0x1E88E5) }
    public var track: Color { dark ? hex(0x3A3A3F) : hex(0xE3E3E8) }

    func direction(_ up: Bool?) -> Color {
        switch up {
        case .some(true): return self.up
        case .some(false): return down
        case .none: return secondary
        }
    }
}
```

`Sources/NetWorthViews/Components.swift`:

```swift
import SwiftUI
import NetWorthCore

struct Label11: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.system(size: 11)).foregroundStyle(Palette(scheme).secondary).lineLimit(1) }
}

struct Headline: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    let size: CGFloat
    var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: 3) {
            Text(state.headlineAmount).font(.system(size: size, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
            Text(state.headlineUnit).font(.system(size: size * 0.5, weight: .bold)).lineLimit(1)
        }
        .foregroundStyle(Palette(scheme).primary)
    }
}

struct ChangeLine: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    var body: some View {
        if let text = state.change {
            Text(text).font(.system(size: 12)).foregroundStyle(Palette(scheme).direction(state.changeUp)).lineLimit(1)
        }
    }
}

struct Footer: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState.Ready
    var body: some View {
        Text(state.hint ?? state.updated).font(.system(size: 10))
            .foregroundStyle(state.hint != nil ? Palette(scheme).warn : Palette(scheme).secondary).lineLimit(1)
    }
}

/// A two-column row: a fixed-width leading label and a trailing value.
struct KeyValueRow: View {
    @Environment(\.colorScheme) private var scheme
    let label: String
    let value: String
    let labelWidth: CGFloat
    var valueColor: Color?
    var body: some View {
        HStack(spacing: 0) {
            Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(Palette(scheme).primary)
                .lineLimit(1).frame(width: labelWidth, alignment: .leading)
            Text(value).font(.system(size: 12)).foregroundStyle(valueColor ?? Palette(scheme).secondary).lineLimit(1)
        }
        .padding(.vertical, 1.5)
    }
}

struct MilestoneRows: View {
    @Environment(\.colorScheme) private var scheme
    let rows: [MilestoneRow]
    var labelWidth: CGFloat = 84
    var body: some View {
        ForEach(Array(rows.enumerated()), id: \.offset) { _, m in
            KeyValueRow(label: m.label, value: m.value, labelWidth: labelWidth, valueColor: m.reached ? Palette(scheme).up : nil)
        }
    }
}

struct GoalBarRow: View {
    @Environment(\.colorScheme) private var scheme
    let label: String
    let bar: GoalBar?
    let color: Color
    var body: some View {
        HStack(spacing: 6) {
            Text(label).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1).frame(width: 54, alignment: .leading)
            if let bar {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Palette(scheme).track)
                        Capsule().fill(color).frame(width: max(6, geo.size.width * bar.fraction))
                    }
                }
                .frame(height: 6)
                Text(bar.percent).font(.system(size: 11, weight: .medium)).foregroundStyle(Palette(scheme).primary).lineLimit(1)
            } else {
                Text("算出不可").font(.system(size: 11)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                Spacer(minLength: 0)
            }
        }
    }
}

struct NextGoalBlock: View {
    @Environment(\.colorScheme) private var scheme
    let goal: NextGoal
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Label11("\(goal.label)まで・\(goal.remaining)")
            GoalBarRow(label: "資産の寄与", bar: goal.growth, color: Palette(scheme).up)
            GoalBarRow(label: "時間の進捗", bar: goal.time, color: Palette(scheme).accent)
        }
    }
}

struct PeriodRow: View {
    @Environment(\.colorScheme) private var scheme
    let change: PeriodChange
    var body: some View {
        KeyValueRow(label: change.label, value: change.text, labelWidth: 56, valueColor: Palette(scheme).direction(change.up))
    }
}

struct CompareTable: View {
    @Environment(\.colorScheme) private var scheme
    let rows: [ProjectionCompareRow]
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                Color.clear.frame(width: 44, height: 1)
                Text("積立あり").frame(maxWidth: .infinity, alignment: .leading)
                Text("積立なし").frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
                HStack(spacing: 0) {
                    Text(r.label).font(.system(size: 13, weight: .medium)).frame(width: 44, alignment: .leading)
                    Text(r.withContributions).frame(maxWidth: .infinity, alignment: .leading)
                    Text(r.withoutContributions).foregroundStyle(Palette(scheme).secondary).frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.system(size: 12)).lineLimit(1).padding(.vertical, 1.5)
                .foregroundStyle(Palette(scheme).primary)
            }
        }
    }
}

/// The trend as a line with a soft fill; points are placed by date, so uneven snapshot spacing is drawn honestly.
struct TrendChart: View {
    @Environment(\.colorScheme) private var scheme
    let points: [TrendPoint]

    var body: some View {
        let up = (points.last?.total ?? 0) >= (points.first?.total ?? 0)
        let color = up ? Palette(scheme).up : Palette(scheme).down
        GeometryReader { geo in
            let pad: CGFloat = 5
            let w = max(geo.size.width - 2 * pad, 1)
            let h = max(geo.size.height - 2 * pad, 1)
            let totals = points.map(\.total)
            let lo = totals.min() ?? 0
            let span = max((totals.max() ?? 0) - lo, 1)
            let t0 = points.first?.date.timeIntervalSince1970 ?? 0
            let tSpan = max((points.last?.date.timeIntervalSince1970 ?? 0) - t0, 1)
            let xy: [CGPoint] = points.map {
                CGPoint(x: pad + w * ($0.date.timeIntervalSince1970 - t0) / tSpan, y: pad + h * (1 - ($0.total - lo) / span))
            }
            ZStack {
                Path { p in
                    guard let first = xy.first, let last = xy.last else { return }
                    p.move(to: CGPoint(x: first.x, y: geo.size.height))
                    xy.forEach { p.addLine(to: $0) }
                    p.addLine(to: CGPoint(x: last.x, y: geo.size.height))
                    p.closeSubpath()
                }
                .fill(LinearGradient(colors: [color.opacity(0.33), color.opacity(0)], startPoint: .top, endPoint: .bottom))
                Path { p in
                    guard let first = xy.first else { return }
                    p.move(to: first)
                    xy.dropFirst().forEach { p.addLine(to: $0) }
                }
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                if let last = xy.last { Circle().fill(color).frame(width: 7, height: 7).position(last) }
            }
        }
    }
}
```

`Sources/NetWorthViews/NetWorthWidgetView.swift`:

```swift
import SwiftUI
import NetWorthCore

/// The four widget sizes; each shows more than the last (see the design spec).
public enum LayoutKind: CaseIterable {
    case small, medium, large, extraLarge

    /// Point sizes of the macOS widget families, used by the layout renderer.
    public var size: CGSize {
        switch self {
        case .small: return CGSize(width: 170, height: 170)
        case .medium: return CGSize(width: 364, height: 170)
        case .large: return CGSize(width: 364, height: 382)
        case .extraLarge: return CGSize(width: 715, height: 382)
        }
    }
}

public struct NetWorthWidgetView: View {
    @Environment(\.colorScheme) private var scheme
    let state: WidgetState
    let layout: LayoutKind

    public init(state: WidgetState, layout: LayoutKind) {
        self.state = state
        self.layout = layout
    }

    public var body: some View {
        Group {
            switch state {
            case .ready(let s):
                switch layout {
                case .small: SmallLayout(s: s)
                case .medium: MediumLayout(s: s)
                case .large: LargeLayout(s: s)
                case .extraLarge: ExtraLargeLayout(s: s)
                }
            case .empty(let message): MessageView(text: message)
            case .notConfigured: MessageView(text: "アプリを開いて設定してください")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct MessageView: View {
    @Environment(\.colorScheme) private var scheme
    let text: String
    var body: some View {
        Text(text).font(.system(size: 13)).multilineTextAlignment(.center)
            .foregroundStyle(Palette(scheme).secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SmallLayout: View {
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label11("純資産")
            Headline(state: s, size: 24)
            ChangeLine(state: s)
            Spacer(minLength: 4)
            Footer(state: s)
        }
    }
}

private struct MediumLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 0) {
                Label11("純資産")
                Headline(state: s, size: 24)
                ChangeLine(state: s)
                Spacer(minLength: 4)
                Footer(state: s)
            }
            .fixedSize(horizontal: true, vertical: false)      // the headline keeps its natural width; the milestones get the rest
            VStack(alignment: .leading, spacing: 0) {
                let next = s.nextMilestones(3)
                if next.isEmpty {
                    Text("全ての目標を達成しました").font(.system(size: 13, weight: .medium)).foregroundStyle(Palette(scheme).up)
                } else {
                    Label11("次の目標")
                    MilestoneRows(rows: next, labelWidth: 78)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct LargeLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Label11("純資産")
            Headline(state: s, size: 30)
            ChangeLine(state: s)
            if let oneYear = s.oneYear {
                Text("1年後の予想 ≈ \(oneYear)").font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            Label11("目標までの期間")
            Text(s.assumption).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
            MilestoneRows(rows: s.milestones)
            if !s.projections.isEmpty {
                Spacer(minLength: 6)
                Label11("将来の予想純資産")
                ForEach(Array(s.projections.enumerated()), id: \.offset) { _, p in
                    KeyValueRow(label: p.label, value: p.value, labelWidth: 84)
                }
            }
            Spacer(minLength: 4)
            Footer(state: s)
        }
    }
}

private struct ExtraLargeLayout: View {
    @Environment(\.colorScheme) private var scheme
    let s: WidgetState.Ready
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 18) {
                VStack(alignment: .leading, spacing: 0) {
                    Label11("純資産")
                    Headline(state: s, size: 30)
                    ChangeLine(state: s)
                    if let oneYear = s.oneYear {
                        Text("1年後の予想 ≈ \(oneYear)").font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                    }
                    if let goal = s.nextGoal {
                        Spacer().frame(height: 8)
                        NextGoalBlock(goal: goal)
                    }
                    if !s.periodChanges.isEmpty || s.peak != nil {
                        Spacer().frame(height: 8)
                        Label11("増減の推移")
                        ForEach(Array(s.periodChanges.enumerated()), id: \.offset) { _, c in PeriodRow(change: c) }
                        if let peak = s.peak {
                            Text(peak).font(.system(size: 12)).foregroundStyle(Palette(scheme).secondary).lineLimit(1).padding(.vertical, 1.5)
                        }
                    }
                    Spacer(minLength: 4)
                    Footer(state: s)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: 0) {
                    Label11("目標までの期間")
                    Text(s.assumption).font(.system(size: 10)).foregroundStyle(Palette(scheme).secondary).lineLimit(1)
                    MilestoneRows(rows: s.milestones)
                    if !s.projectionTable.isEmpty {
                        Spacer().frame(height: 8)
                        Label11("将来の予想純資産")
                        CompareTable(rows: s.projectionTable)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if s.trend.count >= 2 {
                Spacer().frame(height: 6)
                Label11("推移 (過去\(s.trendSpan))")
                TrendChart(points: s.trend).frame(minHeight: 36, maxHeight: .infinity)
            }
        }
    }
}
```

`Sources/NetWorthViews/SampleData.swift`:

```swift
import Foundation
import NetWorthCore

/// Synthetic data (not real figures) for the widget gallery, the placeholder and the layout renderer.
public enum SampleData {
    public static func history(now: Date) -> HistoryResponse {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        var snapshots: [Snapshot] = []
        let yearly: [(String, Double)] = [
            ("2020-07-01T12:00:00Z", 2_000), ("2021-07-01T12:00:00Z", 8_500), ("2022-07-01T12:00:00Z", 14_000),
            ("2023-07-01T12:00:00Z", 17_000), ("2024-07-01T12:00:00Z", 18_000), ("2025-07-01T12:00:00Z", 29_000),
        ]
        for (i, y) in yearly.enumerated() { snapshots.append(Snapshot(id: Int64(i), timestamp: y.0, currency: "GBP", grandTotal: y.1)) }
        for day in stride(from: 60, through: 0, by: -1) {
            let i = Double(60 - day)
            let total = 41_000 + i * 140 + 900 * sin(i * 0.9)
            let at = now.addingTimeInterval(-Double(day) * 86_400)
            snapshots.append(Snapshot(id: Int64(100 + 60 - day), timestamp: f.string(from: at), currency: "GBP", grandTotal: total))
        }
        return HistoryResponse(
            snapshots: snapshots.filter { (f.date(from: $0.timestamp) ?? now) <= now },
            expectedNetWorthAt40: 90_000, expectedNetWorthAt50: 180_000, expectedNetWorthAt60: 360_000, expectedNetWorthAt65: 510_000,
            expectedNetWorthWithContributionsAt40: 255_000, expectedNetWorthWithContributionsAt50: 675_000,
            expectedNetWorthWithContributionsAt60: 1_500_000, expectedNetWorthWithContributionsAt65: 2_180_000,
            estimatedNetWorth1y: 64_000, monthlyContribution: 1_000)
    }

    public static func state(now: Date = Date(), calendar: Calendar = .current, contributions: Bool = true) -> WidgetState {
        WidgetModel.build(
            cache: CacheSnapshot(fetchedAt: Int64(now.timeIntervalSince1970 * 1000) - 38 * 60_000, history: history(now: now)),
            config: WidgetConfig(withContributions: contributions), settings: AppSettings(baseUrl: "https://sample/", apiKey: "k"),
            status: SyncStatus(), now: now, calendar: calendar)
    }
}
```

- [ ] **Step 3: Add the renderer**

`Sources/LayoutRenderer/main.swift`:

```swift
import AppKit
import SwiftUI
import NetWorthCore
import NetWorthViews

// Dev-only: renders every widget size to a PNG using the synthetic sample data.
//   swift run LayoutRenderer <output-dir>

var calendar = Calendar(identifier: .gregorian)
calendar.timeZone = TimeZone(identifier: "UTC")!
let now = ISO8601DateFormatter().date(from: "2026-09-30T12:38:00Z")!

func state(contributions: Bool = true, fetchedAgoMin: Int64 = 38) -> WidgetState {
    let history = SampleData.history(now: now)
    return WidgetModel.build(
        cache: CacheSnapshot(fetchedAt: Int64(now.timeIntervalSince1970 * 1000) - fetchedAgoMin * 60_000, history: history),
        config: WidgetConfig(withContributions: contributions), settings: AppSettings(baseUrl: "https://sample/", apiKey: "k"),
        status: SyncStatus(), now: now, calendar: calendar)
}

@MainActor func render(_ name: String, _ state: WidgetState, _ layout: LayoutKind, _ scheme: ColorScheme, into dir: URL) {
    let palette = Palette(scheme)
    let view = NetWorthWidgetView(state: state, layout: layout)
        .environment(\.colorScheme, scheme)
        .frame(width: layout.size.width, height: layout.size.height)
        .background(palette.background)
        .clipShape(RoundedRectangle(cornerRadius: 22))
    let renderer = ImageRenderer(content: view)
    renderer.scale = 2
    guard let tiff = renderer.nsImage?.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
          let png = rep.representation(using: .png, properties: [:]) else { print("FAILED \(name)"); return }
    try? png.write(to: dir.appendingPathComponent("\(name).png"))
    print("wrote \(name).png")
}

let outDir = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "renders", isDirectory: true)
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

MainActor.assumeIsolated {
    let ready = state()
    for layout in LayoutKind.allCases {
        for scheme in [ColorScheme.dark, .light] {
            render("\(layout)-\(scheme == .dark ? "dark" : "light")", ready, layout, scheme, into: outDir)
        }
    }
    render("large-no-contributions", state(contributions: false), .large, .dark, into: outDir)
    render("extraLarge-no-contributions", state(contributions: false), .extraLarge, .dark, into: outDir)
    render("extraLarge-stale", state(fetchedAgoMin: 500), .extraLarge, .dark, into: outDir)
    render("small-not-configured", .notConfigured, .small, .dark, into: outDir)
    render("medium-empty", .empty("初回の同期を待っています"), .medium, .dark, into: outDir)
}
```

- [ ] **Step 4: Build and render**

Run: `cd net-worth-macos && swift build 2>&1 | grep -E "error|Build" && swift run LayoutRenderer renders 2>&1 | grep -E "wrote|FAILED"`
Expected: `Build complete!` and 13 `wrote ….png` lines (small/medium/large/extraLarge in dark and light, plus large-no-contributions, extraLarge-no-contributions, extraLarge-stale, small-not-configured, medium-empty), no `FAILED`.

- [ ] **Step 5: Look at the renders and check them**

Read `renders/extraLarge-dark.png`, `large-dark.png`, `medium-light.png`, `small-dark.png`, `extraLarge-stale.png`. Expected:
- **small:** 純資産 label, big amount with a small ポンド, change line, updated text at the bottom.
- **medium:** left: the same summary; right: 次の目標 with three milestone rows whose values are fully visible (e.g. `3年1か月 (33歳)`, no `…`).
- **large:** one column: change, `1年後の予想 ≈ …`, 目標までの期間 with the assumptions line and five rows, 将来の予想純資産 with four rows, updated text; nothing cut off at the bottom.
- **extraLarge:** two columns. Left: summary, `10万ポンドまで・あと3年1か月` with a green 資産の寄与 bar and a blue 時間の進捗 bar with one-decimal percentages, 増減の推移 (7日間, 30日間, 全期間, 最高値), updated text. Right: five milestones and the 積立あり / 積立なし table. A trend chart with a caption `推移 (過去N…)` fills the bottom.
- **extraLarge-stale:** the footer reads `古いデータ — サーバーに接続できません` in orange.
- **light variants:** dark text on white; the same layout.
If anything is clipped or misaligned, fix `Components.swift` / `NetWorthWidgetView.swift` and re-render before continuing.

- [ ] **Step 6: Make the widget target depend on `NetWorthViews`**

In `project.yml`, under `NetWorthWidget` → `dependencies`, add:

```yaml
      - package: NetWorthMac
        product: NetWorthViews
```

- [ ] **Step 7: Run the full suite**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2`
Expected: `Executed 116 tests, with 0 failures`.

- [ ] **Step 8: Commit (once approved)**

```bash
git add net-worth-macos/Package.swift net-worth-macos/project.yml net-worth-macos/Sources/NetWorthViews net-worth-macos/Sources/LayoutRenderer
git commit -m "macos: add SwiftUI layouts for the four widget sizes and an offscreen renderer"
```

---

### Task 9: The widget extension

**Files:**
- Delete: `net-worth-macos/Widget/PlaceholderWidget.swift`
- Create: `net-worth-macos/Widget/NetWorthWidget.swift`

**Interfaces:**
- Consumes: `SharedEnvironment.make()`, `Sync`, `EntryPlan`, `WidgetModel`, `WidgetConfig`, `NetWorthWidgetView`, `LayoutKind`, `Palette`, `SampleData`.
- Produces: the WidgetKit widget `kind == "NetWorthWidget"`, display name 純資産, families small/medium/large/extraLarge, configuration intent `NetWorthIntent(withContributions: Bool = true)`.

- [ ] **Step 1: Replace the placeholder with the real widget**

Run: `cd net-worth-macos && rm Widget/PlaceholderWidget.swift`

`Widget/NetWorthWidget.swift`:

```swift
import AppIntents
import NetWorthCore
import NetWorthViews
import SwiftUI
import WidgetKit

/// Edit Widget → 積立を含める (default on), like the Android widget's per-widget option.
struct NetWorthIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "純資産"
    static var description = IntentDescription("目標までの期間に毎月の積立を含めるかどうかを選びます。")

    @Parameter(title: "積立を含める", default: true)
    var withContributions: Bool
}

struct NetWorthEntry: TimelineEntry {
    let date: Date
    let state: WidgetState
    let dashboardURL: URL?
}

struct NetWorthProvider: AppIntentTimelineProvider {
    typealias Entry = NetWorthEntry
    typealias Intent = NetWorthIntent

    func placeholder(in context: Context) -> NetWorthEntry {
        NetWorthEntry(date: Date(), state: SampleData.state(), dashboardURL: nil)
    }

    func snapshot(for configuration: NetWorthIntent, in context: Context) async -> NetWorthEntry {
        // The widget gallery preview uses sample data; a real snapshot renders from the cache without touching the network.
        if context.isPreview { return NetWorthEntry(date: Date(), state: SampleData.state(), dashboardURL: nil) }
        return entries(for: configuration, start: Date()).first!
    }

    func timeline(for configuration: NetWorthIntent, in context: Context) async -> Timeline<NetWorthEntry> {
        let env = SharedEnvironment.make()
        await Sync(cache: env.cache, source: { env.settings.source() }, onResult: env.settings.recordSync).refresh()
        let now = Date()
        return Timeline(entries: entries(for: configuration, start: now), policy: .after(now.addingTimeInterval(EntryPlan.refreshAfter)))
    }

    /// Entries built from the cache only (never the network), one per `EntryPlan` step.
    private func entries(for configuration: NetWorthIntent, start: Date) -> [NetWorthEntry] {
        let env = SharedEnvironment.make()
        let settings = env.settings.load()
        let url = settings.webUrl.isEmpty ? nil : UrlPolicy.parse(settings.webUrl)
        return EntryPlan.states(
            cache: env.cache.load(), config: WidgetConfig(withContributions: configuration.withContributions),
            settings: settings, status: env.settings.status(), start: start, calendar: .current
        ).map { NetWorthEntry(date: $0.date, state: $0.state, dashboardURL: url) }
    }
}

struct NetWorthEntryView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme
    let entry: NetWorthEntry

    private var layout: LayoutKind {
        switch family {
        case .systemSmall: return .small
        case .systemMedium: return .medium
        case .systemLarge: return .large
        default: return .extraLarge
        }
    }

    var body: some View {
        NetWorthWidgetView(state: entry.state, layout: layout)
            .widgetURL(entry.dashboardURL)      // nil opens the host app
            .containerBackground(for: .widget) { Palette(scheme).background }
    }
}

@main
struct NetWorthWidgetBundle: WidgetBundle {
    var body: some Widget { NetWorthWidget() }
}

struct NetWorthWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "NetWorthWidget", intent: NetWorthIntent.self, provider: NetWorthProvider()) { entry in
            NetWorthEntryView(entry: entry)
        }
        .configurationDisplayName("純資産")
        .description("純資産、目標までの期間、将来の予想を表示します。")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
        .contentMarginsDisabled()
    }
}
```

- [ ] **Step 2: Regenerate and build**

```bash
cd net-worth-macos && xcodegen generate && \
xcodebuild -project NetWorth.xcodeproj -scheme NetWorth -configuration Release \
  -derivedDataPath build -allowProvisioningUpdates build 2>&1 | grep -E "error:|warning: .*NetWorth|BUILD" | head
```

Expected: `** BUILD SUCCEEDED **`. If Swift complains about the `Intent` typealias or `Context`, follow the compiler's suggestion (WidgetKit's provider protocols are strict about the exact method signatures above).

- [ ] **Step 3: Verify the bundle structure and entitlements**

```bash
APP=build/Build/Products/Release/NetWorth.app
ls "$APP/Contents/PlugIns"                                   # expect NetWorthWidget.appex
plutil -extract NSExtension.NSExtensionPointIdentifier raw "$APP/Contents/PlugIns/NetWorthWidget.appex/Contents/Info.plist"   # expect com.apple.widgetkit-extension
codesign -d --entitlements :- "$APP/Contents/PlugIns/NetWorthWidget.appex" 2>/dev/null | plutil -p - | grep -E "application-groups|keychain-access-groups|network"
```

Expected: the appex is present, the extension point is `com.apple.widgetkit-extension`, and the entitlements list the app group, keychain group and network client.

- [ ] **Step 4: Run the full suite and commit (once approved)**

Run: `cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2` → `Executed 116 tests, with 0 failures`.

```bash
git add net-worth-macos/Widget net-worth-macos/project.yml
git commit -m "macos: add the WidgetKit extension (timeline provider, intent, four families)"
```

---

### Task 10: Settings window, install script, README and end-to-end check

**Files:**
- Create: `net-worth-macos/App/SettingsView.swift`
- Modify: `net-worth-macos/App/NetWorthApp.swift`
- Delete (optional): `net-worth-macos/App/SelfTest.swift` is kept; it is harmless and useful for signing diagnostics.
- Create: `net-worth-macos/install.sh`, `net-worth-macos/README.md`

**Interfaces:**
- Consumes: `SharedEnvironment.make()`, `SettingsValidator`, `Sync`, `Money`, `AppSettings.defaultBaseUrl`, `WidgetCenter`.

- [ ] **Step 1: Write the settings window**

`App/SettingsView.swift`:

```swift
import NetWorthCore
import SwiftUI
import WidgetKit

/// URL, key and dashboard address, plus "Save & test": validates, fetches once, shows the latest total or the error, and
/// reloads every widget. The Keychain and App Group are shared with the widget extension.
struct SettingsView: View {
    @State private var baseUrl = AppSettings.defaultBaseUrl
    @State private var apiKey = ""
    @State private var webUrl = ""
    @State private var errors: [Field: String] = [:]
    @State private var status: String?
    @State private var busy = false
    @State private var summary = ""

    private let env = SharedEnvironment.make()

    var body: some View {
        Form {
            Section {
                field("API URL", text: $baseUrl, error: errors[.baseUrl])
                secureField("Access key", text: $apiKey, error: errors[.apiKey])
                field("Dashboard URL (optional)", text: $webUrl, error: errors[.webUrl])
            } header: {
                Text("純資産 — connection").font(.headline)
            }
            Section {
                HStack {
                    Button("Save & test", action: saveAndTest).disabled(busy).keyboardShortcut(.defaultAction)
                    if busy { ProgressView().controlSize(.small) }
                }
                if let status { Text(status).foregroundStyle(status.hasPrefix("OK") ? Color.green : Color.secondary) }
                if !summary.isEmpty { Text(summary).font(.footnote).foregroundStyle(.secondary) }
            }
            Section {
                Text("Add the widget: right-click the desktop → Edit Widgets → search 純資産. The widget refreshes about once an hour; macOS decides the exact timing.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .padding(.vertical, 8)
        .onAppear(perform: load)
    }

    private func field(_ title: String, text: Binding<String>, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            TextField(title, text: text).textContentType(.URL).autocorrectionDisabled()
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }

    private func secureField(_ title: String, text: Binding<String>, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            SecureField(title, text: text)
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
        }
    }

    private func load() {
        let s = env.settings.load()
        if !s.baseUrl.isEmpty { baseUrl = s.baseUrl }
        apiKey = s.apiKey
        webUrl = s.webUrl
        summary = syncSummary()
    }

    private func saveAndTest() {
        switch SettingsValidator.validate(baseUrl: baseUrl, apiKey: apiKey, webUrl: webUrl) {
        case .invalid(let e):
            errors = e
            status = nil
        case .valid(let settings):
            errors = [:]
            busy = true
            status = "Connecting…"
            Task {
                do { try env.settings.save(settings) } catch {
                    status = ApiError.storage("\(error)").userMessage
                    busy = false
                    return
                }
                let sync = Sync(cache: env.cache, source: { env.settings.source() }, onResult: env.settings.recordSync)
                switch await sync.refresh() {
                case .failed(let error):
                    status = error.userMessage
                case .updated:
                    if let latest = env.cache.load().history?.snapshots.last {
                        status = "OK — latest \(Money.full(latest.grandTotal, unit: Money.unit(latest.currency)))"
                    } else {
                        status = "OK — no snapshots yet"
                    }
                }
                WidgetCenter.shared.reloadAllTimelines()
                summary = syncSummary()
                busy = false
            }
        }
    }

    private func syncSummary() -> String {
        let s = env.settings.status()
        if s.lastSuccessAt > 0 {
            let date = Date(timeIntervalSince1970: Double(s.lastSuccessAt) / 1000)
            return "Last synced " + date.formatted(date: .abbreviated, time: .shortened)
        }
        return s.lastError ?? "Not synced yet"
    }
}
```

Replace the window content in `App/NetWorthApp.swift`:

```swift
import SwiftUI

@main
struct NetWorthApp: App {
    init() {
        // `NetWorth --self-test` checks the App Group and Keychain group from the command line, then exits.
        if CommandLine.arguments.contains("--self-test") { exit(SelfTest.run()) }
    }

    var body: some Scene {
        WindowGroup("純資産") { SettingsView() }
            .windowResizability(.contentSize)
    }
}
```

- [ ] **Step 2: Write the install script**

`install.sh`:

```bash
#!/usr/bin/env bash
# Builds the app and widget with your signing identity, installs to /Applications and opens the settings window.
set -euo pipefail
cd "$(dirname "$0")"
xcodegen generate
xcodebuild -project NetWorth.xcodeproj -scheme NetWorth -configuration Release \
  -derivedDataPath build -allowProvisioningUpdates build | tail -3
APP="build/Build/Products/Release/NetWorth.app"
pkill -x NetWorth 2>/dev/null || true
rm -rf /Applications/NetWorth.app
ditto "$APP" /Applications/NetWorth.app
open /Applications/NetWorth.app
echo "Installed. Enter the URL and key, press Save & test, then right-click the desktop → Edit Widgets → search 純資産."
```

Run: `chmod +x net-worth-macos/install.sh`

- [ ] **Step 3: Write the README**

`README.md`:

````markdown
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
- `App/NetWorth --self-test` (run the binary inside the bundle) checks the App Group and Keychain group: useful if signing changes.
- The projection assumptions (7% a year, milestones, birth date) are duplicated from the Android widget, the dashboard and
  the backend; change all copies together (see the comment in `Projection.swift`).
````

- [ ] **Step 4: Run the full suite, then build and install**

```bash
cd net-worth-macos && swift test 2>&1 | grep -E "error:|Executed .* tests" | tail -2
./install.sh
```

Expected: `Executed 116 tests, with 0 failures`, then `** BUILD SUCCEEDED **` and the settings window opens.

- [ ] **Step 5: Verify the installed app and that macOS registered the widget**

```bash
/Applications/NetWorth.app/Contents/MacOS/NetWorth --self-test; echo "exit=$?"
pluginkit -mAv -p com.apple.widgetkit-extension | grep -i networth
```

Expected: `SELF-TEST OK` / `exit=0`, and one `com.isym444.networth.widget` line from `pluginkit`. If `pluginkit` shows nothing, run `open /Applications/NetWorth.app` once and retry; if it is still missing, `killall chronod NotificationCenter` and retry.

- [ ] **Step 6: Hand over to the user for the on-screen check**

Ask the user to: enter the URL and key in the window and press **Save & test** (expect `OK — latest 48,838ポンド` or the current total), right-click the desktop → Edit Widgets → 純資産, add each size, and send screenshots. Compare against the renders from Task 8. Things only their Mac can confirm: the real widget point sizes (the renderer uses 170×170, 364×170, 364×382 and 715×382), refresh timing, and the desktop look. Fix any layout differences by adjusting `NetWorthWidgetView.swift` and rerunning `install.sh`.

- [ ] **Step 7: Commit (once approved)**

```bash
git add net-worth-macos/App net-worth-macos/install.sh net-worth-macos/README.md
git commit -m "macos: add the settings window, install script and README"
```

---

## Self-Review

**Spec coverage.** Form (native WidgetKit): Tasks 8-9. Data flow (widget fetches itself, cache in App Group, 401/403 and 6-hour hints): Tasks 6, 7, 9. App Intent option: Task 9. Tap URL: Task 9. Settings window and Keychain: Tasks 6, 10. Risk (App Group / Keychain on a personal team) and its fallback: Task 1. Model and formatting (Money, Projection, GoalProgress, WidgetModel with all fields): Tasks 2-5. Layouts for the four families, palette, trend chart, goal bars: Task 8. Error and empty states: Task 5 (tests) and Task 8 (renders). Testing (ported tests, worked example, real-shape decode fixture, layout renders, build check): Tasks 3-8, 10. Delivery (README, install script): Task 10.

**Deviations from the spec, on purpose:** the trend chart is drawn with SwiftUI `Path` instead of Swift Charts, so the offscreen renderer can draw it deterministically; the SwiftUI layouts live in a separate `NetWorthViews` package target so the renderer (and `swift test`) can use them without WidgetKit. The spec is updated to say so.

**Placeholder scan.** None; every code step carries its code, and every command has an expected result.

**Type consistency.** Names used across tasks match the Interfaces blocks: `Money.unit/number/full/compact`, `Projection.monthsToReach/reach/ageAfterMonths`, `GoalProgress.compute(p:t:c:annualReturn:)`, `WidgetModel.build(cache:config:settings:status:now:calendar:)`, `SharedEnvironment.make()`, `EntryPlan.states(...)`, `NetWorthWidgetView(state:layout:)`, `LayoutKind`, `SampleData.state(now:calendar:contributions:)`.

**Review Focus.** Every line in the Review Focus section names the test that pins it.
