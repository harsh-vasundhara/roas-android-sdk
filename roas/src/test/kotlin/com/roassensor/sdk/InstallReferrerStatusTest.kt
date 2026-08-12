package com.roassensor.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions that turn a referrer read into a diagnosis: what an OK read
 * actually told us, and whether a failed one is worth retrying.
 *
 * Both are pure functions with no Android dependency, so they run on the JVM
 * alongside the hash-parity tests — which matters, because the behaviour they
 * encode was previously only observable by installing on a physical Galaxy
 * tablet from the Play Store.
 */
class InstallReferrerStatusTest {

    // ── classify: a successful READ is not a successful ATTRIBUTION ──────────

    @Test
    fun `a referrer carrying our click id is a real attribution`() {
        assertEquals("OK", InstallReferrerReader.classify("utm_source=meta&rsclid=AbC123"))
    }

    @Test
    fun `a campaign name with no click id still counts as real`() {
        assertEquals("OK", InstallReferrerReader.classify("utm_source=meta&utm_campaign=spring"))
        assertEquals("OK", InstallReferrerReader.classify("rs_campaign=spring"))
    }

    @Test
    fun `Play's organic default is not a failure`() {
        // Nobody clicked an ad. This is a true answer, and must never be
        // counted as a broken device.
        assertEquals(
            "OK_ORGANIC",
            InstallReferrerReader.classify("utm_source=google-play&utm_medium=organic"),
        )
    }

    @Test
    fun `not set means the referrer was dropped — the Galaxy M32 Tab S6 Lite case`() {
        // This is the failure that had to be found by hand on borrowed
        // hardware. Merging it into a flat "OK" is what made it uncountable.
        assertEquals(
            "OK_NOT_SET",
            InstallReferrerReader.classify("utm_source=(not set)&utm_medium=(not set)"),
        )
        // Play sometimes hands it back percent-encoded.
        assertEquals(
            "OK_NOT_SET",
            InstallReferrerReader.classify("utm_source=not%20set&utm_medium=not%20set"),
        )
    }

    @Test
    fun `a real click id beats the organic marker when both are present`() {
        // A campaign can legitimately be tagged utm_medium=organic; the rsclid
        // is the stronger signal and must win, or a tagged campaign would be
        // written off as organic.
        assertEquals(
            "OK",
            InstallReferrerReader.classify("utm_medium=organic&rsclid=AbC123"),
        )
    }

    @Test
    fun `an empty referrer is distinct from both`() {
        assertEquals("OK_EMPTY", InstallReferrerReader.classify(""))
    }

    // ── isTransient: which failures are worth another launch ─────────────────

    @Test
    fun `service failures retry`() {
        // Play Services simply wasn't ready yet — most likely on the very first
        // cold launch after a store install, and likelier still on a slow
        // tablet. This is the case that used to be lost permanently.
        assertTrue(InstallReferrerReader.isTransient("SERVICE_UNAVAILABLE"))
        assertTrue(InstallReferrerReader.isTransient("SERVICE_DISCONNECTED"))
        assertTrue(InstallReferrerReader.isTransient("EXCEPTION:DeadObjectException"))
        assertTrue(InstallReferrerReader.isTransient("UNKNOWN_42"))
    }

    @Test
    fun `permanent failures do not retry`() {
        // A Play Store too old to have the API will not grow one, and our own
        // DEVELOPER_ERROR will not fix itself. Retrying only burns launches.
        assertFalse(InstallReferrerReader.isTransient("FEATURE_NOT_SUPPORTED"))
        assertFalse(InstallReferrerReader.isTransient("DEVELOPER_ERROR"))
        assertFalse(InstallReferrerReader.isTransient("PERMISSION_ERROR"))
    }

    @Test
    fun `every OK variant is final`() {
        // Including OK_NOT_SET: Play bakes the referrer at install time, so a
        // dropped one never reappears. Retrying it would be a busy loop that
        // never recovers anything.
        for (status in listOf("OK", "OK_ORGANIC", "OK_NOT_SET", "OK_EMPTY")) {
            assertFalse(status, InstallReferrerReader.isTransient(status))
        }
    }
}
