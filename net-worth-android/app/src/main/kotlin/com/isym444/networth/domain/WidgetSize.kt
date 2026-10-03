package com.isym444.networth.domain

/**
 * Which layout to draw for the size the launcher gives the widget; each step up shows more:
 * SMALL = headline, MEDIUM = + next three milestones, LARGE = + every milestone and the one-year estimate,
 * XLARGE = + the assumptions and the age 40/50/60/65 projections,
 * FULL = everything WIDE shows, stacked in one column for a tall, phone-width widget (the "full screen" widget),
 * WIDE = two columns: also recent changes, the all-time high and both projection scenarios, with progress to the next goal
 * and to the contribution breakeven underneath.
 * The width thresholds leave room for Japanese text, which is wider than Latin text at the same size.
 */
enum class WidgetSize {
    SMALL, MEDIUM, LARGE, XLARGE, FULL, WIDE;

    companion object {
        fun forDp(widthDp: Float, heightDp: Float): WidgetSize = when {
            widthDp >= 380f && heightDp >= 300f -> WIDE
            widthDp >= 280f && heightDp >= FULL_MIN_HEIGHT_DP -> FULL
            widthDp >= 280f && heightDp >= 390f -> XLARGE
            widthDp >= 280f && heightDp >= 300f -> LARGE
            widthDp >= 340f && heightDp >= 110f -> MEDIUM
            else -> SMALL
        }
    }
}

/** The single-column full layout needs about this much height (dp); the "full screen" widget declares it as its minimum. */
const val FULL_MIN_HEIGHT_DP = 500f
