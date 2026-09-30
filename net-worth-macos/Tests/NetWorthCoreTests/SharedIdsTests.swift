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
