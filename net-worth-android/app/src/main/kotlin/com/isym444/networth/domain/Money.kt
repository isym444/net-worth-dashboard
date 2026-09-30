package com.isym444.networth.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

object Money {
    /**
     * The currency as a Japanese suffix ("ポンド"), appended after the amount. Unknown currencies use their
     * code with a leading space (" CHF"); blank gets no unit.
     */
    fun unit(code: String): String {
        val c = code.trim().uppercase(Locale.ROOT)
        return when (c) {
            "" -> ""
            "GBP" -> "ポンド"
            "EUR" -> "ユーロ"
            "USD" -> "ドル"
            "JPY" -> "円"
            "MXN" -> "ペソ"
            else -> " $c"
        }
    }

    /** Whole units with grouping and no currency: "184,320", "-1,235". Never prints "-0". */
    fun number(amount: Double): String {
        val r = amount.roundToLong()
        val sign = if (r < 0) "-" else ""
        return sign + String.format(Locale.UK, "%,d", r.absoluteValue)
    }

    /** Whole units with grouping: "184,320ポンド", "-1,235ポンド". */
    fun full(amount: Double, unit: String): String = number(amount) + unit

    /**
     * Short form in Japanese units for tight spaces: "10万ポンド", "6.5万ポンド", "100万ポンド", "1億ポンド", "999ポンド".
     * 万 = 10,000 and 億 = 100,000,000.
     */
    fun compact(amount: Double, unit: String): String {
        val a = amount.absoluteValue
        val wan = BigDecimal.valueOf(a / 10_000).setScale(1, RoundingMode.HALF_UP)
        val body = when {
            wan >= BigDecimal(10_000) -> BigDecimal.valueOf(a / 100_000_000).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "億"
            wan.signum() > 0 && a >= 10_000 -> wan.stripTrailingZeros().toPlainString() + "万"
            else -> String.format(Locale.UK, "%,d", a.roundToLong())
        }
        val sign = if (amount < 0 && body != "0") "-" else ""
        return sign + body + unit
    }
}
