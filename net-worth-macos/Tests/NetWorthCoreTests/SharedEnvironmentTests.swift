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
