import Foundation

public struct MilestoneRow: Equatable { public let label: String; public let value: String; public let reached: Bool }
public struct ProjectionRow: Equatable { public let label: String; public let value: String }
public struct ProjectionCompareRow: Equatable {
    public let label: String; public let withContributions: String; public let withoutContributions: String
}
public struct PeriodChange: Equatable { public let label: String; public let text: String; public let up: Bool? }
public struct TrendPoint: Equatable { public let date: Date; public let total: Double }
/// A line of text with one number the view highlights: "給料日まで" + "23" + "日".
public struct StatLine: Equatable {
    public let lead: String
    public let value: String
    public let tail: String
    public let negative: Bool
    public var text: String { lead + value + tail }
}
public struct GoalBar: Equatable { public let fraction: Double; public let percent: String }
public struct NextGoal: Equatable {
    public let label: String       // "10万ポンド"
    public let remaining: String   // "あと3年1か月"
    public let growth: GoalBar     // share of the target that comes from money you already hold (資産の寄与)
    public let time: GoalBar?      // share of the journey from zero already behind you (時間の進捗); nil when undefined
}

public enum WidgetState: Equatable {
    case notConfigured
    case empty(String)
    case ready(Ready)

    public struct Ready: Equatable {
        public let headlineAmount: String            // "184,320"
        public let headlineUnit: String              // "ポンド" (drawn smaller, next to the amount)
        public let change: String?                   // "+1,234ポンド (+0.7%)" / "変化なし" / nil when there is no previous snapshot
        public let changeUp: Bool?                   // true = up, false = down, nil = neutral
        public let updated: String                   // "3時間前に更新"
        public let daysToPayday: Int                 // calendar days until the next payday (>= 1)
        public let payday: StatLine                  // "給料日まで12日"
        public let maxDailySpend: StatLine?            // "1日の上限 38ポンド"; nil when the latest snapshot has no Chase/Amex figures
        public let maxDailySpendBuffer: StatLine?      // "400ポンド確保で 12ポンド"
        public let hint: String?                     // "キーが拒否されました — …" / "古いデータ — …" / nil
        public let oneYear: String?                  // "21万ポンド"
        public let assumption: String                // "年7%運用・毎月1,000ポンド積立"
        public let milestones: [MilestoneRow]        // all five, in order
        public let projections: [ProjectionRow]
        public let projectionTable: [ProjectionCompareRow]
        public let nextGoal: NextGoal?               // nil when every milestone is reached
        public let breakeven: NextGoal?              // net worth where a month of return >= the monthly contribution; nil without contributions
        public let periodChanges: [PeriodChange]     // 7日間 / 30日間 / 全期間, where history allows
        public let peak: String?                     // "最高値 49,153ポンド"
        public let trend: [TrendPoint]               // totals over the last two years, oldest first
        public let trendSpan: String                 // "2年間", "5か月間", "12日間"

        public var headline: String { headlineAmount + headlineUnit }

        /// The next `n` milestones that are not yet reached (for the medium layout).
        public func nextMilestones(_ n: Int) -> [MilestoneRow] { Array(milestones.filter { !$0.reached }.prefix(n)) }
    }
}

/// Pure: (cache, config, settings, sync status, now, calendar) -> what to draw. No UI, no I/O.
public enum WidgetModel {
    public static let staleAfterMs: Int64 = 6 * 60 * 60 * 1000
    public static let trendWindowDays = 730     // the chart covers the last two years...
    public static let trendMaxPoints = 200      // ...thinned to this many points

    public static func build(
        cache: CacheSnapshot, config: WidgetConfig, settings: AppSettings, status: SyncStatus, now: Date, calendar: Calendar
    ) -> WidgetState {
        guard let history = cache.history else {
            if !settings.isConfigured { return .notConfigured }
            if status.keyRejected { return .empty("キーが拒否されました — アプリを開いて修正してください") }
            return .empty("初回の同期を待っています")
        }
        guard let latest = history.snapshots.last else { return .empty("データがまだありません") }
        let previous = history.snapshots.count >= 2 ? history.snapshots[history.snapshots.count - 2] : nil
        let unit = Money.unit(latest.currency)
        let today = calendar.startOfDay(for: now)

        let (change, up) = changeLine(
            delta: previous.map { latest.grandTotal - $0.grandTotal }, previousTotal: previous?.grandTotal, unit: unit)

        let contribution = config.withContributions ? (history.monthlyContribution ?? 0) : 0
        let milestones = ProjectionConfig.milestones.map { target -> MilestoneRow in
            let reach = Projection.reach(
                current: latest.grandTotal, target: target, monthlyContribution: contribution, today: today, calendar: calendar)
            let reached: Bool
            if case .reached = reach { reached = true } else { reached = false }
            return MilestoneRow(label: Money.compact(target, unit: unit), value: reach.text(), reached: reached)
        }

        let points: [(date: Date, total: Double)] = history.snapshots
            .compactMap { snap in parseInstant(snap.timestamp).map { ($0, snap.grandTotal) } }
            .sorted { $0.0 < $1.0 }
        let trendPoints = trend(of: points)

        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let ageMs = nowMs - cache.fetchedAt
        let hint: String?
        if status.keyRejected {
            hint = "キーが拒否されました — アプリで更新してください"
        } else if cache.fetchedAt > 0 && ageMs > staleAfterMs {
            hint = "古いデータ — サーバーに接続できません"
        } else {
            hint = nil
        }

        let daysToPayday = Payday.daysUntilNext(from: now, calendar: calendar)
        // The server thins old snapshots (one per past month), so the real peak can be missing from `points`; it sends it separately.
        let peakTotal = [points.map(\.total).max(), history.peakNetWorth].compactMap { $0 }.max()
        let peak = peakTotal.map { "最高値 " + Money.full($0, unit: unit) }

        return .ready(.init(
            headlineAmount: Money.number(latest.grandTotal),
            headlineUnit: unit,
            change: change,
            changeUp: up,
            updated: updatedText(ageMs),
            daysToPayday: daysToPayday,
            payday: StatLine(lead: "給料日まで", value: "\(daysToPayday)", tail: "日", negative: false),
            maxDailySpend: DailySpend.perDay(snapshot: latest, days: daysToPayday)
                .map { spendLine(lead: "1日の上限 ", amount: $0, unit: unit) },
            maxDailySpendBuffer: DailySpend.perDay(snapshot: latest, days: daysToPayday, buffer: DailySpend.investBuffer)
                .map { spendLine(lead: "\(Money.full(DailySpend.investBuffer, unit: unit))確保で ", amount: $0, unit: unit) },
            hint: hint,
            oneYear: history.estimatedNetWorth1y.map { Money.compact($0, unit: unit) },
            assumption: assumption(contribution: contribution, unit: unit),
            milestones: milestones,
            projections: projections(history, withContributions: config.withContributions, unit: unit),
            projectionTable: projectionTable(history, unit: unit),
            nextGoal: nextGoal(total: latest.grandTotal, unit: unit, contribution: contribution),
            breakeven: breakeven(total: latest.grandTotal, unit: unit, contribution: contribution),
            periodChanges: periodChanges(points, unit: unit),
            peak: peak,
            trend: trendPoints.map { TrendPoint(date: $0.date, total: $0.total) },
            trendSpan: trendPoints.first.map { spanText(days: daysBetween($0.date, trendPoints.last!.date)) } ?? ""
        ))
    }

    // MARK: - pieces

    private static func spendLine(lead: String, amount: Double, unit: String) -> StatLine {
        StatLine(lead: lead, value: Money.number(amount), tail: unit, negative: Money.roundHalfUp(amount) < 0)
    }

    static func changeLine(delta: Double?, previousTotal: Double?, unit: String) -> (String?, Bool?) {
        guard let delta, let previousTotal else { return (nil, nil) }
        if Money.roundHalfUp(delta) == 0 { return ("変化なし", nil) }
        let pct = previousTotal != 0 ? String(format: " (%+.1f%%)", delta / abs(previousTotal) * 100) : ""
        return ((delta > 0 ? "+" : "-") + Money.full(abs(delta), unit: unit) + pct, delta > 0)
    }

    private static func projections(_ h: HistoryResponse, withContributions: Bool, unit: String) -> [ProjectionRow] {
        let byAge: [(Int, Double?)] = withContributions
            ? [(40, h.expectedNetWorthWithContributionsAt40), (50, h.expectedNetWorthWithContributionsAt50),
               (60, h.expectedNetWorthWithContributionsAt60), (65, h.expectedNetWorthWithContributionsAt65)]
            : [(40, h.expectedNetWorthAt40), (50, h.expectedNetWorthAt50), (60, h.expectedNetWorthAt60), (65, h.expectedNetWorthAt65)]
        return byAge.compactMap { age, value in value.map { ProjectionRow(label: "\(age)歳", value: Money.compact($0, unit: unit)) } }
    }

    private static func projectionTable(_ h: HistoryResponse, unit: String) -> [ProjectionCompareRow] {
        func cell(_ v: Double?) -> String { v.map { Money.compact($0, unit: unit) } ?? "-" }
        let rows: [(Int, Double?, Double?)] = [
            (40, h.expectedNetWorthWithContributionsAt40, h.expectedNetWorthAt40),
            (50, h.expectedNetWorthWithContributionsAt50, h.expectedNetWorthAt50),
            (60, h.expectedNetWorthWithContributionsAt60, h.expectedNetWorthAt60),
            (65, h.expectedNetWorthWithContributionsAt65, h.expectedNetWorthAt65),
        ]
        return rows.filter { $0.1 != nil || $0.2 != nil }
            .map { ProjectionCompareRow(label: "\($0.0)歳", withContributions: cell($0.1), withoutContributions: cell($0.2)) }
    }

    /// Snapshots from the last `trendWindowDays` days before the latest one, evenly thinned to `trendMaxPoints`
    /// (keeps the first and last).
    private static func trend(of points: [(date: Date, total: Double)]) -> [(date: Date, total: Double)] {
        guard let latestAt = points.last?.date else { return [] }
        let cutoff = latestAt.addingTimeInterval(-Double(trendWindowDays) * 86_400)
        let recent = points.filter { $0.date >= cutoff }
        if recent.count <= trendMaxPoints { return recent }
        return (0..<trendMaxPoints).map { i in recent[i * (recent.count - 1) / (trendMaxPoints - 1)] }
    }

    private static func spanText(days: Int) -> String {
        if days < 60 { return "\(days)日間" }
        if days < 700 { return "\(Money.roundHalfUp(Double(days) / 30.4))か月間" }
        return "\(Money.roundHalfUp(Double(days) / 365.25))年間"
    }

    /// Change over the last 7 and 30 days (only when history goes back that far) and since the first snapshot ("全期間").
    private static func periodChanges(_ points: [(date: Date, total: Double)], unit: String) -> [PeriodChange] {
        guard points.count >= 2, let last = points.last, let first = points.first else { return [] }
        var out: [PeriodChange] = []
        for (days, label) in [(7, "7日間"), (30, "30日間")] {
            let cutoff = last.date.addingTimeInterval(-Double(days) * 86_400)
            guard let base = points.last(where: { $0.date <= cutoff }) else { continue }
            let (text, up) = changeLine(delta: last.total - base.total, previousTotal: base.total, unit: unit)
            if let text { out.append(PeriodChange(label: label, text: text, up: up)) }
        }
        let (text, up) = changeLine(delta: last.total - first.total, previousTotal: first.total, unit: unit)
        if let text { out.append(PeriodChange(label: "全期間", text: text, up: up)) }
        return out
    }

    private static func nextGoal(total: Double, unit: String, contribution: Double) -> NextGoal? {
        guard let target = ProjectionConfig.milestones.first(where: { $0 > total }) else { return nil }
        let progress = GoalProgress.compute(p: total, t: target, c: contribution, annualReturn: ProjectionConfig.annualReturn)
        let monthsLeft = Projection.monthsToReach(current: total, target: target, monthlyContribution: contribution)
        let remaining = monthsLeft.map { "あと\($0 / 12)年\($0 % 12)か月" } ?? "あと100年以上"
        return NextGoal(
            label: Money.compact(target, unit: unit), remaining: remaining,
            growth: goalBar(progress.growthShare), time: progress.timeShare.map(goalBar))
    }

    /// The net worth at which one month of growth equals the monthly contribution (`c / r`, same monthly rate as the projections),
    /// with the same two progress bars as a milestone. `remaining` is the money still to go, or "達成済み".
    private static func breakeven(total: Double, unit: String, contribution: Double) -> NextGoal? {
        guard contribution > 0 else { return nil }
        let target = Projection.breakevenNetWorth(monthlyContribution: contribution)
        let progress = GoalProgress.compute(p: total, t: target, c: contribution, annualReturn: ProjectionConfig.annualReturn)
        return NextGoal(
            label: Money.compact(target, unit: unit),
            remaining: total >= target ? "達成済み" : "あと" + Money.compact(target - total, unit: unit),
            growth: goalBar(progress.growthShare), time: progress.timeShare.map(goalBar))
    }

    private static func goalBar(_ share: Double) -> GoalBar { GoalBar(fraction: share, percent: String(format: "%.1f%%", share * 100)) }

    private static func assumption(contribution: Double, unit: String) -> String {
        let rate = "年\(Money.roundHalfUp(ProjectionConfig.annualReturn * 100))%運用"
        return contribution > 0 ? "\(rate)・毎月\(Money.full(contribution, unit: unit))積立" : "\(rate)・積立なし"
    }

    private static func updatedText(_ ms: Int64) -> String {
        if ms < 60_000 { return "たった今更新" }                    // also covers a fetch time in the future (clock skew)
        if ms < 3_600_000 { return "\(ms / 60_000)分前に更新" }
        if ms < 86_400_000 { return "\(ms / 3_600_000)時間前に更新" }
        return "\(ms / 86_400_000)日前に更新"
    }

    private static func daysBetween(_ a: Date, _ b: Date) -> Int { Int(b.timeIntervalSince(a) / 86_400) }

    /// Parses "2026-08-01T07:00:03.533248Z" and "2020-07-01T12:00:00Z" (fractional seconds are dropped).
    static func parseInstant(_ s: String) -> Date? {
        var t = s
        if let r = t.range(of: #"\.\d+"#, options: .regularExpression) { t.removeSubrange(r) }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: t)
    }
}
