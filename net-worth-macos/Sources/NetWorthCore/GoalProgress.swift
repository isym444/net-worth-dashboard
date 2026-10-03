import Foundation

/// How far along the way to a target you are, in two ways (both on the same constant-return assumption as the milestones):
///  - `growthShare`: the share of the target that will have come from the money you already hold, once it has grown until
///    the target is reached: `P(1+r)^n / T`.
///  - `timeShare`: the share of the whole journey from zero (same monthly contribution and return) that is already behind
///    you: `(n0 - n) / n0`. nil when it is undefined (no monthly contribution).
/// `monthsRemaining` is `n`, the (fractional) months still to go; nil when the target can never be reached.
public struct GoalProgress: Equatable {
    public let monthsRemaining: Double?
    public let growthShare: Double
    public let timeShare: Double?

    public init(monthsRemaining: Double?, growthShare: Double, timeShare: Double?) {
        self.monthsRemaining = monthsRemaining
        self.growthShare = growthShare
        self.timeShare = timeShare
    }

    /// `p` current savings, `t` target, `c` monthly contribution (end of month), `annualReturn` as a decimal.
    public static func compute(p: Double, t: Double, c: Double, annualReturn: Double) -> GoalProgress {
        if p >= t { return GoalProgress(monthsRemaining: 0, growthShare: 1, timeShare: 1) }
        let r = pow(1 + annualReturn, 1.0 / 12) - 1

        if r <= 0 {                                             // no growth: contributions are the only driver
            if c <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: clamp(p / t), timeShare: nil) }
            let share = clamp(p / t)
            return GoalProgress(monthsRemaining: (t - p) / c, growthShare: share, timeShare: share)
        }

        let logGrowth = log(1 + r)
        if c <= 0 {                                             // no contributions: it is all your existing money
            if p <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: 0, timeShare: nil) }
            return GoalProgress(monthsRemaining: log(t / p) / logGrowth, growthShare: 1, timeShare: nil)
        }

        let k = c / r
        if p + k <= 0 { return GoalProgress(monthsRemaining: nil, growthShare: 0, timeShare: 0) }   // contributions never catch up
        let n = log((t + k) / (p + k)) / logGrowth
        let n0 = log(1 + t * r / c) / logGrowth
        let growth = p > 0 ? clamp(p * pow(1 + r, n) / t) : 0
        return GoalProgress(monthsRemaining: n, growthShare: growth, timeShare: clamp((n0 - n) / n0))
    }

    private static func clamp(_ x: Double) -> Double { min(max(x, 0), 1) }
}
