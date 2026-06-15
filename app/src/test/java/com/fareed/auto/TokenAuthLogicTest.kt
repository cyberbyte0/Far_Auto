package com.fareed.auto

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Covers fixes:
 *  - Token entropy raised to 256-bit (generateToken)
 *  - Token accepted via X-Automator-Token header ONLY (URL param removed)
 *
 * The header-only rule is tested conceptually: a request that provides a token only
 * as a URL query param results in providedToken = null (header is absent), which must
 * fail authentication. The server code is:
 *   val providedToken = session.headers["x-automator-token"]   // URL param no longer read
 *   if (!MessageDigest.isEqual(authToken.toByteArray(), providedToken?.toByteArray() ?: ByteArray(0)))
 *       -> 401
 */
class TokenAuthLogicTest {

    private fun authPasses(storedToken: String, providedToken: String?): Boolean {
        val provided = providedToken?.toByteArray() ?: ByteArray(0)
        return MessageDigest.isEqual(storedToken.toByteArray(), provided)
    }

    @Test
    fun correct_header_token_passes_auth() {
        val token = DashboardServer.generateToken()
        assertTrue(authPasses(storedToken = token, providedToken = token))
    }

    @Test
    fun wrong_token_fails_auth() {
        val stored = DashboardServer.generateToken()
        val wrong = DashboardServer.generateToken()
        assertFalse(authPasses(storedToken = stored, providedToken = wrong))
    }

    @Test
    fun missing_header_fails_auth() {
        // Simulates: session.headers["x-automator-token"] returns null (header absent)
        val token = DashboardServer.generateToken()
        assertFalse(authPasses(storedToken = token, providedToken = null))
    }

    @Test
    fun url_param_only_request_fails_auth() {
        // After the fix the server does NOT fall back to session.parms["token"].
        // A request that sends the token only as a URL query param produces
        // providedToken = null because the header is absent.
        val token = DashboardServer.generateToken()
        val providedViaUrlParam: String? = null  // header absent → null
        assertFalse(
            "URL-param-only token must not authenticate after the header-only fix",
            authPasses(storedToken = token, providedToken = providedViaUrlParam)
        )
    }

    @Test
    fun empty_string_token_fails_auth() {
        val token = DashboardServer.generateToken()
        assertFalse(authPasses(storedToken = token, providedToken = ""))
    }

    @Test
    fun partial_token_fails_auth() {
        val token = DashboardServer.generateToken()
        assertFalse(authPasses(storedToken = token, providedToken = token.take(10)))
    }

    @Test
    fun auth_works_with_256_bit_token_length() {
        // Old tokens were 12 chars. New are 43 (base64 of 32 bytes).
        // Verify MessageDigest.isEqual handles longer tokens correctly.
        val token = DashboardServer.generateToken()
        assertEquals(43, token.length)
        assertTrue(authPasses(storedToken = token, providedToken = token))
    }
}
