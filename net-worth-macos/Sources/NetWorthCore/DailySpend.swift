import Foundation

/// "Max Daily Spend" maths, like `dailySpend` / `dailySpendToInvestBuffer` (dashboard.component.ts) except that the widget
/// does not subtract the monthly subscriptions: what is left on Chase after Amex, spread over the days to the next payday.
public enum DailySpend {
    /// Reserved to invest at month end in the "buffer" variant.
    public static let investBuffer = 400.0

    /// `nil` when the snapshot has no Chase/Amex figures (history from before those columns existed).
    public static func perDay(snapshot: Snapshot, days: Int, buffer: Double = 0) -> Double? {
        guard let chase = snapshot.chaseTotal, let amex = snapshot.amexDebtTotal else { return nil }
        return (chase - amex - buffer) / Double(max(1, days))
    }
}
