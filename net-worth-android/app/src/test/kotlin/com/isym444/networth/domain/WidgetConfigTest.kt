package com.isym444.networth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigTest {
    @Test fun defaultsToWithContributions() = assertTrue(WidgetConfig().withContributions)

    @Test fun roundTrips() {
        val c = WidgetConfig(withContributions = false)
        assertEquals(c, WidgetConfig.fromJson(c.toJson()))
        assertFalse(WidgetConfig.fromJson(c.toJson()).withContributions)
    }

    @Test fun nullBlankAndCorruptJsonFallBackToDefaults() {
        assertEquals(WidgetConfig(), WidgetConfig.fromJson(null))
        assertEquals(WidgetConfig(), WidgetConfig.fromJson("  "))
        assertEquals(WidgetConfig(), WidgetConfig.fromJson("{ not json"))
    }

    @Test fun unknownKeysAreIgnored() =
        assertEquals(WidgetConfig(withContributions = false), WidgetConfig.fromJson("""{"withContributions":false,"future":1}"""))
}
