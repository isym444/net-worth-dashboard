import Foundation

/// Lenient on input (unknown keys ignored, null or missing for a defaulted field -> default), omits nils on output.
public struct Snapshot: Codable, Equatable {
    public var id: Int64
    public var timestamp: String
    public var currency: String
    public var grandTotal: Double
    public var chaseTotal: Double?        // null on old snapshots, which predate the column
    public var amexDebtTotal: Double?

    public init(
        id: Int64 = 0, timestamp: String = "", currency: String = "GBP", grandTotal: Double = 0,
        chaseTotal: Double? = nil, amexDebtTotal: Double? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.currency = currency
        self.grandTotal = grandTotal
        self.chaseTotal = chaseTotal
        self.amexDebtTotal = amexDebtTotal
    }

    private enum CodingKeys: String, CodingKey { case id, timestamp, currency, grandTotal, chaseTotal, amexDebtTotal }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int64.self, forKey: .id) ?? 0
        timestamp = try c.decodeIfPresent(String.self, forKey: .timestamp) ?? ""
        currency = try c.decodeIfPresent(String.self, forKey: .currency) ?? "GBP"
        grandTotal = try c.decodeIfPresent(Double.self, forKey: .grandTotal) ?? 0
        chaseTotal = try c.decodeIfPresent(Double.self, forKey: .chaseTotal)
        amexDebtTotal = try c.decodeIfPresent(Double.self, forKey: .amexDebtTotal)
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
    public var peakNetWorth: Double?                     // highest total over ALL snapshots; `snapshots` is thinned by the server

    public init(
        snapshots: [Snapshot],
        expectedNetWorthAt40: Double? = nil, expectedNetWorthAt50: Double? = nil,
        expectedNetWorthAt60: Double? = nil, expectedNetWorthAt65: Double? = nil,
        expectedNetWorthWithContributionsAt40: Double? = nil, expectedNetWorthWithContributionsAt50: Double? = nil,
        expectedNetWorthWithContributionsAt60: Double? = nil, expectedNetWorthWithContributionsAt65: Double? = nil,
        estimatedNetWorth1y: Double? = nil, monthlyContribution: Double? = nil, peakNetWorth: Double? = nil
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
        self.peakNetWorth = peakNetWorth
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
