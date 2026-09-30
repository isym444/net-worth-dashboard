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
