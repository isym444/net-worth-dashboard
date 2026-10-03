import Foundation

/// Currency formatting in Japanese units: "184,320ポンド", "10万ポンド", "1億ポンド". Never uses £/$/€.
public enum Money {
    /// The currency as a Japanese suffix appended after the amount. Unknown currencies use their code with a
    /// leading space (" CHF"); blank gets no unit.
    public static func unit(_ code: String) -> String {
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch c {
        case "": return ""
        case "GBP": return "ポンド"
        case "EUR": return "ユーロ"
        case "USD": return "ドル"
        case "JPY": return "円"
        case "MXN": return "ペソ"
        default: return " \(c)"
        }
    }

    /// Whole units with grouping and no currency: "184,320", "-1,235". Never prints "-0".
    public static func number(_ amount: Double) -> String {
        let r = roundHalfUp(amount)
        return (r < 0 ? "-" : "") + grouped(abs(r))
    }

    /// Whole units with grouping: "184,320ポンド", "-1,235ポンド".
    public static func full(_ amount: Double, unit: String) -> String { number(amount) + unit }

    /// Short form in Japanese units: "10万ポンド", "6.5万ポンド", "100万ポンド", "1億ポンド", "999ポンド".
    /// 万 = 10,000 and 億 = 100,000,000.
    public static func compact(_ amount: Double, unit: String) -> String {
        let a = abs(amount)
        let wan = decimal(a / 10_000, scale: 1)
        let body: String
        if wan >= 10_000 {
            body = plain(decimal(a / 100_000_000, scale: 2)) + "億"
        } else if wan > 0 && a >= 10_000 {
            body = plain(wan) + "万"
        } else {
            body = grouped(roundHalfUp(a))
        }
        let sign = (amount < 0 && body != "0") ? "-" : ""
        return sign + body + unit
    }

    /// Rounds half up (toward positive infinity), like Kotlin's `roundToLong`.
    static func roundHalfUp(_ x: Double) -> Int64 { Int64((x + 0.5).rounded(.down)) }

    static func grouped(_ n: Int64) -> String {
        let digits = String(n)
        var out = ""
        for (i, ch) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(ch)
        }
        return out
    }

    /// Rounds the shortest decimal representation of `x` half up to `scale` places (like Java's `BigDecimal.valueOf`).
    private static func decimal(_ x: Double, scale: Int) -> Decimal {
        var d = Decimal(string: String(x)) ?? Decimal(x)
        var r = Decimal()
        NSDecimalRound(&r, &d, scale, .plain)
        return r
    }

    private static func plain(_ d: Decimal) -> String { NSDecimalNumber(decimal: d).stringValue }
}
