package com.fareed.auto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instrumented tests for DashboardServer — run on device/emulator.
 *
 * Covers:
 *  - Security headers (X-Content-Type-Options, X-Frame-Options, CSP) on dashboard route
 *  - Token accepted via X-Automator-Token header only
 *  - URL query-param token correctly rejected (fix #5)
 *  - Wrong / missing token returns 401
 */
@RunWith(AndroidJUnit4::class)
class DashboardServerInstrumentedTest {

    private lateinit var server: DashboardServer
    private val port = 19080
    private val token = "instrumented-test-token-abc123"

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        DashboardServer.authToken = token
        server = DashboardServer(ctx, port)
        server.start()
        Thread.sleep(200) // give NanoHTTPD a moment to bind
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private data class Resp(val code: Int, val headers: Map<String, List<String>>)

    private fun get(path: String, headerToken: String? = null): Resp {
        val conn = URL("http://localhost:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        if (headerToken != null) conn.setRequestProperty("X-Automator-Token", headerToken)
        val code = conn.responseCode
        val headers = conn.headerFields
        runCatching { conn.inputStream.close() }
        runCatching { conn.errorStream?.close() }
        conn.disconnect()
        return Resp(code, headers)
    }

    // ── dashboard HTML route ──────────────────────────────────────────────────

    @Test
    fun dashboard_returns_200_without_token() {
        // The "/" route is intentionally unauthenticated (serves the HTML shell)
        assertEquals(200, get("/").code)
    }

    @Test
    fun dashboard_has_x_content_type_options_header() {
        val resp = get("/")
        val value = resp.headers.entries
            .firstOrNull { it.key?.lowercase() == "x-content-type-options" }
            ?.value?.firstOrNull()
        assertEquals("nosniff", value)
    }

    @Test
    fun dashboard_has_x_frame_options_header() {
        val resp = get("/")
        val value = resp.headers.entries
            .firstOrNull { it.key?.lowercase() == "x-frame-options" }
            ?.value?.firstOrNull()
        assertEquals("DENY", value)
    }

    @Test
    fun dashboard_has_content_security_policy_header() {
        val resp = get("/")
        val csp = resp.headers.entries
            .firstOrNull { it.key?.lowercase() == "content-security-policy" }
            ?.value?.firstOrNull()
        assertNotNull("CSP header must be present", csp)
        assertTrue("CSP must restrict connect-src", csp!!.contains("connect-src"))
        assertTrue("CSP must allow cdnjs for Ace editor", csp.contains("cdnjs.cloudflare.com"))
    }

    // ── token auth ────────────────────────────────────────────────────────────

    @Test
    fun correct_header_token_returns_200() {
        assertEquals(200, get("/status", headerToken = token).code)
    }

    @Test
    fun wrong_header_token_returns_401() {
        assertEquals(401, get("/status", headerToken = "wrong-token").code)
    }

    @Test
    fun missing_token_returns_401() {
        assertEquals(401, get("/status").code)
    }

    @Test
    fun url_param_token_is_rejected_with_401() {
        // Before fix: server also read session.parms["token"] → would return 200
        // After fix:  only session.headers["x-automator-token"] is checked → 401
        val resp = get("/status?token=$token") // token in URL param, NO header
        assertEquals(
            "Token passed as URL query param must be rejected after header-only fix",
            401, resp.code,
        )
    }

    @Test
    fun empty_string_token_returns_401() {
        assertEquals(401, get("/status", headerToken = "").code)
    }

    // ── scripts endpoint ──────────────────────────────────────────────────────

    @Test
    fun scripts_endpoint_returns_json_array_with_valid_token() {
        val conn = URL("http://localhost:$port/scripts").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("X-Automator-Token", token)
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        assertTrue("Response must be a JSON array", body.trimStart().startsWith("["))
    }
}
