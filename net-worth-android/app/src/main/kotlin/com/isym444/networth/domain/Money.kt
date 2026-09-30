package com.isym444.networth.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToLong

object Money {
    /** Known symbols; anything else is shown as its code plus a space ("CHF 1,234"); blank gets no symbol. */
    fun symbol(code: String): String {
        val c = code.trim().uppercase(Locale.ROOT)
        return when (c) {
            "" -> ""
            "GBP" -> "£"
            "EUR" -> "€"
            "USD" -> "$"
            "JPY" -> "¥"
            "MXN" -> "MX$"
            else -> "$c "
        }
    }

    /** Whole units with grouping: "£184,320", "-£1,235". Never prints "-£0". */
    fun full(amount: Double, symbol: String): String {
        val r = amount.roundToLong()
        val sign = if (r < 0) "-" else ""
        return sign + symbol + String.format(Locale.UK, "%,d", r.absoluteValue)
    }

    /** Short form for tight spaces: "£100k", "£1.7m", "£999", "-£250k". */
    fun compact(amount: Double, symbol: String): String {
        val a = amount.absoluteValue
        val body = when {
            a >= 1_000_000 -> BigDecimal.valueOf(a / 1_000_000).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "m"
            a >= 1_000 -> (a / 1_000).roundToLong().toString() + "k"
            else -> a.roundToLong().toString()
        }
        val sign = if (amount < 0 && body != "0") "-" else ""
        return sign + symbol + body
    }
}
