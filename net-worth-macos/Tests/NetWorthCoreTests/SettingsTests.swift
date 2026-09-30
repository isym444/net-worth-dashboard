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
