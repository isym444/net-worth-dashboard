package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {
    @Test fun smallIsNarrowOrShort() {
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(110f, 110f))
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(250f, 110f))    // too narrow for two columns of Japanese text
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(180f, 500f))    // narrow beats tall
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(400f, 90f))
    }

    @Test fun mediumIsWideAndShort() {
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(340f, 110f))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(408f, 250f))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(500f, 299f))
    }

    @Test fun largeIsTallEnoughForEveryMilestone() {
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(280f, 300f))
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(379f, 389f))
    }

    @Test fun extraLargeAddsProjections() {
        assertEquals(WidgetSize.XLARGE, WidgetSize.forDp(280f, 390f))
        assertEquals(WidgetSize.XLARGE, WidgetSize.forDp(379f, 700f))
    }

    @Test fun theWidestPortraitHomeScreenWidgetOnTheOppoPadGetsTwoColumns() {
        assertEquals(WidgetSize.WIDE, WidgetSize.forDp(408f, 408f))
    }

    @Test fun wideUsesTwoColumns() {
        assertEquals(WidgetSize.WIDE, WidgetSize.forDp(380f, 300f))
        assertEquals(WidgetSize.WIDE, WidgetSize.forDp(700f, 700f))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(700f, 299f))
    }
}
