import Foundation

/// Projection assumptions. These duplicate values that also live elsewhere; change all copies together:
///  - net-worth-android/.../domain/ProjectionConfig.kt
///  - net-worth-frontend/src/app/dashboard.component.ts: assumedAnnualReturn, netWorthMilestones, birthDate, maxProjectionMonths
///  - net-worth-service PortfolioProjectionService.ANNUAL_GROWTH_RATE and NetWorthHistoryService.BIRTH_DATE
public enum ProjectionConfig {
    public static let annualReturn = 0.07
    public static let maxMonths = 1200
    public static let milestones: [Double] = [100_000, 250_000, 500_000, 750_000, 1_000_000]
    public static let birthDate = DateComponents(year: 1996, month: 6, day: 14)
}

public enum Reach: Equatable {
    case reached(age: Int)
    case inMonths(Int, age: Int)
    case never

    /// Japanese wording: "達成 (30歳)", "4年7か月 (34歳)", "100年以上".
    public func text() -> String {
        switch self {
        case .reached(let age): return "達成 (\(age)歳)"
        case .inMonths(let months, let age): return "\(months / 12)年\(months % 12)か月 (\(age)歳)"
        case .never: return "100年以上"
        }
    }
}

/// Pure port of `timeToReachPortfolio` (dashboard.component.ts).
public enum Projection {
    /// The net worth at which one month of growth equals the monthly contribution (`c / r`; port of `contributionBreakevenNetWorth`).
    public static func breakevenNetWorth(monthlyContribution: Double) -> Double {
        monthlyContribution / (pow(1 + ProjectionConfig.annualReturn, 1.0 / 12) - 1)
    }

    /// 0 if `current >= target`; nil if not reached within `maxMonths` (default `ProjectionConfig.maxMonths`).
    public static func monthsToReach(
        current: Double, target: Double, monthlyContribution: Double, maxMonths: Int = ProjectionConfig.maxMonths
    ) -> Int? {
        if current >= target { return 0 }
        let growth = pow(1 + ProjectionConfig.annualReturn, 1.0 / 12)
        var balance = current
        var months = 0
        while balance < target && months < maxMonths {
            balance = balance * growth + monthlyContribution
            months += 1
        }
        return balance >= target ? months : nil
    }

    public static func reach(
        current: Double, target: Double, monthlyContribution: Double, today: Date, calendar: Calendar
    ) -> Reach {
        guard let months = monthsToReach(current: current, target: target, monthlyContribution: monthlyContribution) else {
            return .never
        }
        let age = ageAfterMonths(today: today, months: months, calendar: calendar)
        return months == 0 ? .reached(age: age) : .inMonths(months, age: age)
    }

    /// Whole years between the birth date and `today + months`. Month-end clamps (31 Jan + 1 month = 28 Feb) match java.time.
    public static func ageAfterMonths(today: Date, months: Int, calendar: Calendar) -> Int {
        let birth = calendar.date(from: ProjectionConfig.birthDate)!
        let later = calendar.date(byAdding: .month, value: months, to: today)!
        return calendar.dateComponents([.year], from: birth, to: later).year ?? 0
    }
}
