package com.fareed.auto

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Covers fix: Token entropy raised from ~71-bit (12 chars) to 256-bit (32 random bytes, base64url).
 */
class GenerateTokenTest {

    @Test
    fun token_is_43_chars() {
        // 32 bytes → 43 base64url chars (no padding)
        assertEquals(43, DashboardServer.generateToken().length)
    }

    @Test
    fun token_decodes_to_32_bytes() {
        val token = DashboardServer.generateToken()
        val bytes = Base64.getUrlDecoder().decode(token)
        assertEquals("Token must encode exactly 32 bytes (256 bits)", 32, bytes.size)
    }

    @Test
    fun token_contains_only_url_safe_base64_chars() {
        val token = DashboardServer.generateToken()
        val valid = Regex("^[A-Za-z0-9_-]+$")
        assertTrue("Token '$token' contains invalid URL-unsafe characters", valid.matches(token))
    }

    @Test
    fun token_has_no_base64_padding() {
        // URL-safe Base64 without padding — padding chars must not appear
        assertFalse(DashboardServer.generateToken().contains('='))
    }

    @Test
    fun two_tokens_are_unique() {
        assertNotEquals(DashboardServer.generateToken(), DashboardServer.generateToken())
    }

    @Test
    fun token_passes_constant_time_comparison_with_itself() {
        val token = DashboardServer.generateToken()
        assertTrue(MessageDigest.isEqual(token.toByteArray(), token.toByteArray()))
    }

    @Test
    fun token_fails_comparison_against_empty_bytes() {
        val token = DashboardServer.generateToken()
        assertFalse(MessageDigest.isEqual(token.toByteArray(), ByteArray(0)))
    }
}
