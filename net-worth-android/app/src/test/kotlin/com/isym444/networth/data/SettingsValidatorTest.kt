package com.isym444.networth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {
    private fun valid(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = ""): AppSettings {
        val v = SettingsValidator.validate(base, key, web)
        assertTrue("expected Valid, was $v", v is Validation.Valid)
        return (v as Validation.Valid).settings
    }

    private fun errors(base: String = "https://nwapi.isym.uk", key: String = "abc123", web: String = ""): Set<Field> {
        val v = SettingsValidator.validate(base, key, web)
        assertTrue("expected Invalid, was $v", v is Validation.Invalid)
        return (v as Validation.Invalid).errors.keys
    }

    @Test fun acceptsAValidTriple() {
        val s = valid(web = "https://net-worth.example.com")
        assertEquals("https://nwapi.isym.uk/", s.baseUrl)
        assertEquals("abc123", s.apiKey)
        assertEquals("https://net-worth.example.com/", s.webUrl)
        assertTrue(s.isConfigured)
    }

    @Test fun trimsAPastedKeyWithATrailingNewline() = assertEquals("abc123", valid(key = "  abc123\n").apiKey)

    @Test fun rejectsInnerWhitespaceInTheKey() = assertEquals(setOf(Field.API_KEY), errors(key = "abc 123"))

    @Test fun rejectsAnEmptyKey() = assertEquals(setOf(Field.API_KEY), errors(key = "  "))

    @Test fun acceptsKeysWithoutAPrefix() = assertEquals("plainsecret", valid(key = "plainsecret").apiKey)

    @Test fun normalisesAMissingScheme() = assertEquals("https://nwapi.isym.uk/", valid(base = "nwapi.isym.uk").baseUrl)

    @Test fun rejectsPlainHttpBaseUrl() = assertEquals(setOf(Field.BASE_URL), errors(base = "http://nwapi.isym.uk"))

    @Test fun rejectsABlankBaseUrl() = assertEquals(setOf(Field.BASE_URL), errors(base = ""))

    @Test fun webUrlIsOptional() = assertEquals("", valid(web = "  ").webUrl)

    @Test fun rejectsABadWebUrl() = assertEquals(setOf(Field.WEB_URL), errors(web = "http://insecure.example.com"))

    @Test fun reportsEveryBadFieldAtOnce() =
        assertEquals(setOf(Field.BASE_URL, Field.API_KEY, Field.WEB_URL), errors(base = "", key = "", web = "http://x.com"))

    @Test fun isConfiguredNeedsBothUrlAndKey() {
        assertFalse(AppSettings(baseUrl = "https://x/", apiKey = "").isConfigured)
        assertFalse(AppSettings(baseUrl = "", apiKey = "k").isConfigured)
        assertTrue(AppSettings(baseUrl = "https://x/", apiKey = "k").isConfigured)
    }
}
