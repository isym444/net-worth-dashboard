import XCTest
@testable import NetWorthCore

final class FileSecretStoreTests: XCTestCase {
    private var dir: URL!
    private var fileURL: URL { dir.appendingPathComponent("api-key") }

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("file-secret-store-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func permissions(of url: URL) throws -> Int {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs[.posixPermissions] as? NSNumber)?.intValue ?? -1
    }

    func testLoadReturnsEmptyWhenNothingWasSaved() {
        XCTAssertEqual(FileSecretStore(url: fileURL).loadKey(), "")
    }

    func testSavedKeyRoundTrips() throws {
        let store = FileSecretStore(url: fileURL)
        try store.saveKey("cd_read_abc123")
        XCTAssertEqual(store.loadKey(), "cd_read_abc123")
    }

    func testAnotherInstanceOnTheSameFileSeesTheKey() throws {
        try FileSecretStore(url: fileURL).saveKey("shared-with-the-widget")
        XCTAssertEqual(FileSecretStore(url: fileURL).loadKey(), "shared-with-the-widget")
    }

    func testSavedFileIsReadableOnlyByTheOwner() throws {
        try FileSecretStore(url: fileURL).saveKey("secret")
        XCTAssertEqual(try permissions(of: fileURL), 0o600)
    }

    func testOverwritingKeepsOwnerOnlyPermissionsAndReplacesTheKey() throws {
        let store = FileSecretStore(url: fileURL)
        try store.saveKey("old")
        try store.saveKey("new")
        XCTAssertEqual(store.loadKey(), "new")
        XCTAssertEqual(try permissions(of: fileURL), 0o600)
    }

    func testSavingAnEmptyKeyRemovesTheFile() throws {
        let store = FileSecretStore(url: fileURL)
        try store.saveKey("something")
        try store.saveKey("")
        XCTAssertEqual(store.loadKey(), "")
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }

    func testSavingEmptyKeyWhenNothingExistsDoesNotThrow() {
        XCTAssertNoThrow(try FileSecretStore(url: fileURL).saveKey(""))
    }

    func testSaveThrowsWhenTheDirectoryIsMissing() {
        let missing = dir.appendingPathComponent("nope/api-key")
        XCTAssertThrowsError(try FileSecretStore(url: missing).saveKey("x"))
    }
}
