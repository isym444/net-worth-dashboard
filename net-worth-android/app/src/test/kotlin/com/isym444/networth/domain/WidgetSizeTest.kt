package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizeTest {
    @Test fun smallIsNarrowOrShort() {
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(110f, 110f))
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(180f, 300f))   // narrow beats tall
        assertEquals(WidgetSize.SMALL, WidgetSize.forDp(300f, 90f))
    }

    @Test fun mediumIsWideAndShort() {
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(250f, 110f))
        assertEquals(WidgetSize.MEDIUM, WidgetSize.forDp(320f, 199f))
    }

    @Test fun largeIsWideAndTall() {
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(250f, 200f))
        assertEquals(WidgetSize.LARGE, WidgetSize.forDp(400f, 500f))
    }
}
