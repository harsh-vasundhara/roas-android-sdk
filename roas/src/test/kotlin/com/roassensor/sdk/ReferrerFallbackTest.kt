package com.roassensor.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ReferrerFallback]'s priority logic in isolation — Play's own answer,
 * whenever it has one, must always win over the OEM channel or the legacy
 * broadcast, since either could be stale or from a different store's own
 * (possibly wrong) convention, while Play's answer — even "definitively
 * organic" — is the platform's own authoritative read. Below Play, the OEM
 * channel (synchronous, carries its own timestamps) outranks the broadcast
 * (racy, thinner).
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

    private fun oemResult(referrer: OemReferrer.Referrer?, status: String) =
        OemReferrer.Result(referrer, status)

    private fun oemReferrer(raw: String) =
        OemReferrer.Referrer(raw, clickTimestampSeconds = null, installTimestampSeconds = null)

    @Test
    fun `Play finding a real referrer wins outright, OEM and broadcast are ignored`() {
        val decision = ReferrerFallback.decide(
            playResult(referrer("rsclid=AbC123"), "OK"),
            oemSource = "vivo",
            oemResult = oemResult(oemReferrer("rsclid=fromVivo"), "OK"),
            storedBroadcastReferrer = "utm_source=vivo_store",
        )
        assertEquals("OK", decision.status)
        assertEquals("google", decision.source)
        assertNull(decision.broadcastReferrer)
        assertNull(decision.oemReferrer)
    }

    @Test
    fun `Play definitively organic still wins over an OEM referrer and a broadcast referrer`() {
        // Play answering "no ad drove this" is itself a real, trustworthy
        // signal — an OEM or broadcast referrer arriving alongside it must
        // not override Play's own authoritative read.
        val decision = ReferrerFallback.decide(
            playResult(referrer("utm_source=google-play&utm_medium=organic"), "OK_ORGANIC"),
            oemSource = "samsung",
            oemResult = oemResult(oemReferrer("rsclid=fromSamsung"), "OK"),
            storedBroadcastReferrer = "utm_source=some_other_store",
        )
        assertEquals("OK_ORGANIC", decision.status)
        assertEquals("google", decision.source)
        assertNull(decision.broadcastReferrer)
        assertNull(decision.oemReferrer)
    }

    @Test
    fun `Play total silence falls to a matching OEM reader's real referrer, broadcast ignored`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "OK_NOT_SET"),
            oemSource = "xiaomi",
            oemResult = oemResult(oemReferrer("rsclid=fromXiaomi&rs_campaign=spring"), "OK"),
            storedBroadcastReferrer = "utm_source=vivo_store&rs_campaign=summer",
        )
        assertEquals("OK_OEM", decision.status)
        assertEquals("xiaomi", decision.source)
        assertEquals("rsclid=fromXiaomi&rs_campaign=spring", decision.oemReferrer?.referrer)
        assertNull(decision.broadcastReferrer)
    }

    @Test
    fun `Play silence and OEM reader also empty falls to a waiting broadcast referrer`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "FEATURE_NOT_SUPPORTED"),
            oemSource = "huawei",
            oemResult = oemResult(null, "OK_EMPTY"),
            storedBroadcastReferrer = "utm_source=vivo_store&rs_campaign=summer",
        )
        assertEquals("OK_BROADCAST", decision.status)
        assertEquals("broadcast", decision.source)
        assertEquals("utm_source=vivo_store&rs_campaign=summer", decision.broadcastReferrer)
        assertNull(decision.oemReferrer)
    }

    @Test
    fun `Play total failure with no OEM reader attempted falls back to a waiting broadcast referrer`() {
        // Device isn't one of the four OEMs — Roas.reportFirstOpen never runs
        // an OEM reader at all, so oemResult is null, exactly as it would be
        // on a stock Pixel.
        val decision = ReferrerFallback.decide(
            playResult(null, "FEATURE_NOT_SUPPORTED"),
            oemSource = "",
            oemResult = null,
            storedBroadcastReferrer = "utm_source=vivo_store&rs_campaign=summer",
        )
        assertEquals("OK_BROADCAST", decision.status)
        assertEquals("broadcast", decision.source)
        assertEquals("utm_source=vivo_store&rs_campaign=summer", decision.broadcastReferrer)
    }

    @Test
    fun `Play failure, no OEM reader, no broadcast either yields nothing, status still reported`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "FEATURE_NOT_SUPPORTED"),
            oemSource = "",
            oemResult = null,
            storedBroadcastReferrer = "",
        )
        assertEquals("FEATURE_NOT_SUPPORTED", decision.status)
        assertEquals("google", decision.source)
        assertNull(decision.broadcastReferrer)
        assertNull(decision.oemReferrer)
    }

    @Test
    fun `a transient Play failure with no OEM match and no broadcast yet also falls through cleanly`() {
        val decision = ReferrerFallback.decide(
            playResult(null, "SERVICE_UNAVAILABLE"),
            oemSource = "",
            oemResult = null,
            storedBroadcastReferrer = "",
        )
        assertEquals("SERVICE_UNAVAILABLE", decision.status)
        assertEquals("google", decision.source)
        assertNull(decision.broadcastReferrer)
    }
}
