import Foundation

/// Paycheck-date maths, ported from `net-worth-frontend/src/app/bank-holidays.ts` (keep the two in step).
/// English bank holidays are computed locally (including the Easter-based ones); no I/O.
public enum Payday {
    public static let dayOfMonth = 23

    /// The next payday strictly after `today`: the 23rd, or the nearest working day before it when that is a weekend or
    /// bank holiday. On payday itself this is next month's, so "days left" never collapses to 0.
    public static func next(after now: Date, calendar: Calendar) -> Date {
        let today = calendar.startOfDay(for: now)
        let thisMonth = adjusted(monthOffset: 0, from: today, calendar: calendar)
        if thisMonth > today { return thisMonth }
        return adjusted(monthOffset: 1, from: today, calendar: calendar)
    }

    /// Calendar days from `now` to the next payday (payday tomorrow = 1). Floored at 1, like the dashboard.
    public static func daysUntilNext(from now: Date, calendar: Calendar) -> Int {
        let today = calendar.startOfDay(for: now)
        let payday = next(after: now, calendar: calendar)
        return max(1, calendar.dateComponents([.day], from: today, to: payday).day ?? 1)
    }

    /// English bank holidays for `year` (start of day), with weekend substitution: a holiday on a weekend moves to the next
    /// free weekday, skipping dates already taken (which also yields the Christmas/Boxing Day double shift).
    public static func bankHolidays(year: Int, calendar: Calendar) -> [Date] {
        func date(_ month: Int, _ day: Int) -> Date { calendar.date(from: DateComponents(year: year, month: month, day: day))! }
        func add(_ d: Date, _ days: Int) -> Date { calendar.date(byAdding: .day, value: days, to: d)! }
        func weekday(_ d: Date) -> Int { calendar.component(.weekday, from: d) }   // 1 = Sunday ... 7 = Saturday
        func isWeekend(_ d: Date) -> Bool { weekday(d) == 1 || weekday(d) == 7 }
        func firstMonday(_ month: Int) -> Date {
            let first = date(month, 1)
            return add(first, (2 - weekday(first) + 7) % 7)
        }
        func lastMonday(_ month: Int) -> Date {
            let last = add(month == 12 ? date(12, 31) : date(month + 1, 1), month == 12 ? 0 : -1)
            return add(last, -((weekday(last) - 2 + 7) % 7))
        }
        func skipWeekend(_ d: Date) -> Date { isWeekend(d) ? add(d, weekday(d) == 7 ? 2 : 1) : d }

        let easter = easterSunday(year: year, calendar: calendar)
        let raw: [Date] = [
            date(1, 1), add(easter, -2), add(easter, 1), firstMonday(5), lastMonday(5), lastMonday(8), date(12, 25), date(12, 26),
        ]
        var used = Set<Date>()
        var out: [Date] = []
        for d in raw {
            var candidate = skipWeekend(d)
            while used.contains(candidate) { candidate = skipWeekend(add(candidate, 1)) }
            used.insert(candidate)
            out.append(candidate)
        }
        return out
    }

    // MARK: - internals

    private static func adjusted(monthOffset: Int, from today: Date, calendar: Calendar) -> Date {
        let base = calendar.date(byAdding: .month, value: monthOffset, to: calendar.date(
            from: calendar.dateComponents([.year, .month], from: today))!)!
        let c = calendar.dateComponents([.year, .month], from: base)
        var candidate = calendar.date(from: DateComponents(year: c.year, month: c.month, day: dayOfMonth))!
        let holidays = Set(bankHolidays(year: c.year!, calendar: calendar))
        while isWeekend(candidate, calendar) || holidays.contains(candidate) {
            candidate = calendar.date(byAdding: .day, value: -1, to: candidate)!
        }
        return candidate
    }

    private static func isWeekend(_ d: Date, _ calendar: Calendar) -> Bool {
        let w = calendar.component(.weekday, from: d)
        return w == 1 || w == 7
    }

    /// Anonymous Gregorian algorithm (Meeus/Jones/Butcher).
    private static func easterSunday(year: Int, calendar: Calendar) -> Date {
        let a = year % 19, b = year / 100, c = year % 100
        let d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4, k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let monthDay = h + l - 7 * m + 114
        return calendar.date(from: DateComponents(year: year, month: monthDay / 31, day: monthDay % 31 + 1))!
    }
}
