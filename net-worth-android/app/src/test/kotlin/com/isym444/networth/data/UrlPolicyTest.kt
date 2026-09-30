package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {
    @Test fun addsHttpsWhenSchemeIsMissing() =
        assertEquals("https://nwapi.isym.uk/", UrlPolicy.parse("nwapi.isym.uk")?.toString())

    @Test fun trimsWhitespace() =
        assertEquals("https://nwapi.isym.uk/", UrlPolicy.parse("  https://nwapi.isym.uk/\n")?.toString())

    @Test fun keepsAPathPrefix() =
        assertEquals("https://example.com/nw/", UrlPolicy.parse("https://example.com/nw/")?.toString())

    @Test fun rejectsPlainHttpToARealHost() = assertNull(UrlPolicy.parse("http://nwapi.isym.uk"))

    @Test fun allowsPlainHttpToLocalDevHosts() {
        assertEquals("http://10.0.2.2:8080/", UrlPolicy.parse("http://10.0.2.2:8080")?.toString())
        assertEquals("http://localhost:8080/", UrlPolicy.parse("http://localhost:8080")?.toString())
    }

    @Test fun rejectsBlankAndGarbage() {
        assertNull(UrlPolicy.parse(""))
        assertNull(UrlPolicy.parse("   "))
        assertNull(UrlPolicy.parse("https://"))
    }
}
