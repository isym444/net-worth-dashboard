package com.isym444.networth.domain

/**
 * Which layout to draw for the size the launcher gives the widget; each step up shows more:
 * SMALL = headline, MEDIUM = + next three milestones, LARGE = + every milestone and the one-year estimate,
 * XLARGE = + the assumptions and the age 40/50/60/65 projections,
 * WIDE = two columns: also progress to the next goal, recent changes, the all-time high and both projection scenarios
 * (and a trend chart when tall enough).
 * The width thresholds leave room for Japanese text, which is wider than Latin text at the same size.
 */
enum class WidgetSize {
    SMALL, MEDIUM, LARGE, XLARGE, WIDE;

    companion object {
        fun forDp(widthDp: Float, heightDp: Float): WidgetSize = when {
            widthDp >= 380f && heightDp >= 300f -> WIDE
            widthDp >= 280f && heightDp >= 390f -> XLARGE
            widthDp >= 280f && heightDp >= 300f -> LARGE
            widthDp >= 340f && heightDp >= 110f -> MEDIUM
            else -> SMALL
        }
    }
}

/** The wide layout adds a trend chart under the two columns when it is at least this tall (dp). */
const val WIDE_CHART_MIN_HEIGHT_DP = 380f
