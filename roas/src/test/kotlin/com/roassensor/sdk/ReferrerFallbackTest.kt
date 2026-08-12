package com.roassensor.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ReferrerFallback]'s priority logic in isolation — Play's own answer,
 * whenever it has one, must always win over the legacy broadcast, since a
 * broadcast referrer could be stale or from a different store's own (possibly
 * wrong) convention, while Play's answer — even "definitively organic" — is
 * the platform's own authoritative read.
 */
class ReferrerFallbackTest {

    private fun playResult(referrer: InstallReferrerReader.Referrer?, status: String) =
        InstallReferrerReader.Result(referrer, status)

    private fun referrer(raw: String) =
        InstallReferrerReader.Referrer(
            referrer = raw,
            clickToInstallSeconds = null,
            clickTimestampSeconds = null,
            installBeginTimestampSeconds = null,
        )

    @Test
    fun `Play finding a real referrer wins outright, broadcast is ignored`() {
        val decision = ReferrerFallback.decide(
            playResult(referrer("rsclid=AbC123"), "OK"),
            storedBroadcastReferrer = "utm_source=vivo_store",
        )
        assertEquals("OK", decision.status)
        assertNull(decision.broadcastReferrer)
    }

    @Test
    fun `Play definitively organic still wins over a broadcast referrer`() {
        // Play answering "no ad drove this" is itself a real, trustworthy
        // signal — a broadcast referrer arriving alongside it must not
        // override Play's own authoritative read.
        val decision = ReferrerFallback.decide(
            playResult(referrer("utm_source=google-play&utm_medium=organic"), "OK_ORGANIC"),
            storedBroadcastReferrer = "utm_source=some_other_store",
        )
        assertEquals("OK_ORGANIC", decision.status)
        assertNull(decision.broadcastReferrer)
    }

    @Test
    fun `Play total failure (non-Play store) falls back to a waiting broadcast referrer`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "FEATURE_NOT_SUPPORTED"),
            storedBroadcastReferrer = "utm_source=vivo_store&rs_campaign=summer",
        )
        assertEquals("OK_BROADCAST", decision.status)
        assertEquals("utm_source=vivo_store&rs_campaign=summer", decision.broadcastReferrer)
    }

    @Test
    fun `Play failure with no broadcast either yields nothing, status still reported`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "FEATURE_NOT_SUPPORTED"),
            storedBroadcastReferrer = "",
        )
        assertEquals("FEATURE_NOT_SUPPORTED", decision.status)
        assertNull(decision.broadcastReferrer)
    }

    @Test
    fun `a transient Play failure with no broadcast yet also falls through cleanly`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "SERVICE_UNAVAILABLE"),
            storedBroadcastReferrer = "",
        )
        assertEquals("SERVICE_UNAVAILABLE", decision.status)
        assertNull(decision.broadcastReferrer)
    }
}
