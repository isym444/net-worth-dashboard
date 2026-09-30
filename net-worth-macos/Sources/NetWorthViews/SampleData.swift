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
