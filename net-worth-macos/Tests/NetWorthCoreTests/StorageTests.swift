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
