import Foundation

/// The widget timeline: one entry every 30 minutes for the next 6 hours, each built from the same cache but with its own
/// "now", so "N分前に更新" advances and the out-of-date hint appears by itself once the data is 6 hours old.
public enum EntryPlan {
    public static let step: TimeInterval = 30 * 60
    public static let count = 12
    /// Ask WidgetKit for a fresh timeline after this long (it budgets refreshes, so the real timing is up to the system).
    public static let refreshAfter: TimeInterval = 60 * 60

    public static func dates(from start: Date) -> [Date] { (0..<count).map { start.addingTimeInterval(Double($0) * step) } }

    public static func states(
        cache: CacheSnapshot, config: WidgetConfig, settings: AppSettings, status: SyncStatus, start: Date, calendar: Calendar
    ) -> [(date: Date, state: WidgetState)] {
        dates(from: start).map {
            ($0, WidgetModel.build(cache: cache, config: config, settings: settings, status: status, now: $0, calendar: calendar))
        }
    }
}
