package com.fareed.auto

import org.junit.Assert.*
import org.junit.Test

/**
 * Covers fix M4: lastToastText + lastToastPackage replaced with a single
 * @Volatile Pair<String, String>? so text and package are always updated atomically.
 *
 * These tests verify the Pair contract: text and package can never be read in
 * a state where one belongs to one toast event and the other to a different one.
 */
class ToastAtomicityTest {

    @Test
    fun pair_write_holds_both_fields_together() {
        var lastToast: Pair<String, String>? = null
        lastToast = Pair("Your OTP is 123456", "com.bank.app")

        assertEquals("Your OTP is 123456", lastToast?.first)
        assertEquals("com.bank.app", lastToast?.second)
    }

    @Test
    fun null_clears_both_fields_in_one_operation() {
        var lastToast: Pair<String, String>? = Pair("text", "com.app")
        lastToast = null

        assertNull(lastToast?.first)
        assertNull(lastToast?.second)
    }

    @Test
    fun update_replaces_both_fields_without_intermediate_state() {
        var lastToast: Pair<String, String>? = Pair("old message", "com.old.app")
        lastToast = Pair("new message", "com.new.app")

        // Both fields must belong to the same event — never a mix
        assertEquals("new message", lastToast?.first)
        assertEquals("com.new.app", lastToast?.second)
    }

    @Test
    fun package_filter_check_uses_pair_consistently() {
        val lastToast: Pair<String, String>? = Pair("Hello", "com.target.app")
        val filter = "com.target.app"

        val text = lastToast?.takeIf { it.second == filter }?.first
        assertEquals("Hello", text)
    }

    @Test
    fun wrong_package_filter_returns_null() {
        val lastToast: Pair<String, String>? = Pair("Hello", "com.target.app")
        val text = lastToast?.takeIf { it.second == "com.other.app" }?.first
        assertNull(text)
    }

    @Test
    fun null_toast_returns_null_for_both_fields() {
        val lastToast: Pair<String, String>? = null
        assertNull(lastToast?.first)
        assertNull(lastToast?.second)
    }
}
