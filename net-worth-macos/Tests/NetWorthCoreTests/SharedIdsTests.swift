import XCTest
@testable import NetWorthCore

final class SharedIdsTests: XCTestCase {
    func testTheInfoPlistKeyIsAbsentFromAPlainTestBundle() {
        XCTAssertNil(SharedIds.appGroup(Bundle(for: SharedIdsTests.self)))
    }
}
