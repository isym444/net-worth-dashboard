package com.isym444.networth.domain

/**
 * Which layout to draw for the size the launcher gives the widget. The thresholds are starting points;
 * tune them against the emulator/tablet in Task 8 (Oppo Pad cells are larger than a phone's).
 */
enum class WidgetSize {
    SMALL, MEDIUM, LARGE;

    companion object {
        fun forDp(widthDp: Float, heightDp: Float): WidgetSize = when {
            widthDp >= 250f && heightDp >= 200f -> LARGE
            widthDp >= 250f && heightDp >= 110f -> MEDIUM
            else -> SMALL
        }
    }
}
