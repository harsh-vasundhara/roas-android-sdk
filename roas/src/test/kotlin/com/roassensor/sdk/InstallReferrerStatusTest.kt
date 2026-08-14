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
    fun `a fetch that never got a callback still retries`() {
        // The vivo V2130 case: Funtouch OS tore down the Play Services IPC
        // connection before either InstallReferrerStateListener method could
        // fire, on every one of 8 real launches over more than a day — a
        // failure that recurs identically on every future launch, not a
        // one-off. InstallReferrerReader.fetch's timeout synthesizes this
        // status so reportFirstOpen still runs (an install gets counted)
        // instead of the device silently never reporting one at all.
        assertTrue(InstallReferrerReader.isTransient("TIMEOUT"))
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

    // ── isAuthoritative: which of Play's own answers may skip the OEM/broadcast
    // fallback entirely, vs. which still need it despite carrying a non-null
    // Referrer object ─────────────────────────────────────────────────────────

    private fun result(referrer: InstallReferrerReader.Referrer?, status: String) =
        InstallReferrerReader.Result(referrer, status)

    private fun realReferrer(raw: String) =
        InstallReferrerReader.Referrer(
            referrer = raw,
            clickToInstallSeconds = null,
            clickTimestampSeconds = null,
            installBeginTimestampSeconds = null,
        )

    @Test
    fun `a real OK read is authoritative — no fallback needed`() {
        assertTrue(InstallReferrerReader.isAuthoritative(result(realReferrer("rsclid=AbC123"), "OK")))
    }

    @Test
    fun `an organic read is authoritative too — nobody clicked an ad is itself a real answer`() {
        assertTrue(
            InstallReferrerReader.isAuthoritative(
                result(realReferrer("utm_source=google-play&utm_medium=organic"), "OK_ORGANIC"),
            ),
        )
    }

    @Test
    fun `OK_NOT_SET is NOT authoritative, even though Play's own Referrer object is non-null`() {
        // The regression this function exists to prevent: a naive
        // `referrer != null` check treats this as final too (Play DID answer,
        // technically), silently discarding a correctly-computed OEM/broadcast
        // fallback on exactly the case it exists for.
        assertFalse(
            InstallReferrerReader.isAuthoritative(
                result(realReferrer("utm_source=(not set)&utm_medium=(not set)"), "OK_NOT_SET"),
            ),
        )
    }

    @Test
    fun `OK_EMPTY is also NOT authoritative, same as OK_NOT_SET`() {
        assertFalse(InstallReferrerReader.isAuthoritative(result(realReferrer(""), "OK_EMPTY")))
    }

    @Test
    fun `a genuine failure with no Referrer object at all is NOT authoritative`() {
        assertFalse(InstallReferrerReader.isAuthoritative(result(null, "FEATURE_NOT_SUPPORTED")))
        assertFalse(InstallReferrerReader.isAuthoritative(result(null, "TIMEOUT")))
    }
}
