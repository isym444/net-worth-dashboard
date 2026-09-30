package com.isym444.networth.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(
        key: String = "secret",
        base: HttpUrl = server.url("/"),
        http: OkHttpClient = ApiClient.DEFAULT_HTTP,
    ) = ApiClient(base, key, http)

    private val body = """{"snapshots":[{"grandTotal":184320.25,"currency":"GBP"}],"monthlyContribution":1000.0}"""

    @Test fun sendsTheKeyInXApiKeyAndHitsTheHistoryPath() {
        server.enqueue(MockResponse().setBody(body))
        runBlocking { client(key = "  secret\n").history() }
        val req = server.takeRequest()
        assertEquals("/api/v1/history", req.path)
        assertEquals("secret", req.getHeader("X-Api-Key"))       // trimmed
        assertNull(req.getHeader("Authorization"))               // this backend does not use Bearer
    }

    @Test fun decodesA200() {
        server.enqueue(MockResponse().setBody(body))
        val h = runBlocking { client().history() }
        assertEquals(184320.25, h.snapshots.single().grandTotal, 0.0)
        assertEquals(1000.0, h.monthlyContribution!!, 0.0)
    }

    @Test fun keepsAPathPrefixOnTheBaseUrl() {
        server.enqueue(MockResponse().setBody(body))
        runBlocking { client(base = server.url("/nw/")).history() }
        assertEquals("/nw/api/v1/history", server.takeRequest().path)
    }

    @Test fun a401IsAKeyRejection() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Missing or invalid X-Api-Key header"}"""))
        val e = assertThrows(ApiError.Http::class.java) { runBlocking { client().history() } }
        assertEquals(401, e.status)
        assertEquals("Missing or invalid X-Api-Key header", e.detail)
        assertTrue(e.userMessage.contains("rejected"))
        assertTrue(e.rejectsKey)
    }

    @Test fun a500CarriesTheSpringMessageButDoesNotRejectTheKey() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"boom"}"""))
        val e = assertThrows(ApiError.Http::class.java) { runBlocking { client().history() } }
        assertEquals("boom", e.detail)
        assertFalse(e.rejectsKey)
    }

    @Test fun anHtmlPageWithA200IsADecodingError() {
        server.enqueue(MockResponse().setBody("<html><body>Welcome</body></html>"))
        assertThrows(ApiError.Decoding::class.java) { runBlocking { client().history() } }
    }

    @Test fun aTimeoutIsANetworkError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val e = assertThrows(ApiError.Network::class.java) { runBlocking { client(http = http).history() } }
        assertFalse(e.rejectsKey)
    }

    @Test fun aKeyWithControlCharactersIsNotConfiguredNotACrash() {
        assertThrows(ApiError.NotConfigured::class.java) { runBlocking { client(key = "ab\u0001cd").history() } }
    }
}
