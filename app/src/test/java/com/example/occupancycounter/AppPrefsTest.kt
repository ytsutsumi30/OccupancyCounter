package com.example.occupancycounter

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPrefsTest {

    @Test
    fun parseSendIntervalSec_returnsDefaultForNullOrInvalid() {
        assertEquals(10, AppPrefs.parseSendIntervalSec(null))
        assertEquals(10, AppPrefs.parseSendIntervalSec("abc"))
    }

    @Test
    fun parseSendIntervalSec_returnsParsedValueForNumber() {
        assertEquals(5, AppPrefs.parseSendIntervalSec("5"))
    }

    @Test
    fun resolveServerEndpoint_migratesLegacyEndpointsToDefault() {
        assertEquals(AppPrefs.DEFAULT_ENDPOINT, AppPrefs.resolveServerEndpoint(AppPrefs.LEGACY_ENDPOINT))
        assertEquals(
            AppPrefs.DEFAULT_ENDPOINT,
            AppPrefs.resolveServerEndpoint(AppPrefs.LEGACY_ENDPOINT_API_OCCUPANCY)
        )
    }

    @Test
    fun resolveServerEndpoint_keepsCurrentOrNullValue() {
        val custom = "https://example.com/ingest/headcount"
        assertEquals(AppPrefs.DEFAULT_ENDPOINT, AppPrefs.resolveServerEndpoint(null))
        assertEquals(custom, AppPrefs.resolveServerEndpoint(custom))
    }
}

