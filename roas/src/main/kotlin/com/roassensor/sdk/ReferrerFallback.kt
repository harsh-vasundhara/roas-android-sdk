package com.roassensor.sdk

/**
 * Which referrer signal an install beacon should carry, in priority order:
 * Google Play's own Install Referrer result if it found one; else the
 * matching OEM's own native referrer channel (Vivo/Huawei/Xiaomi/Samsung —
 * see [OemDevice]) if that device is one of those and it answered; else the
 * legacy `INSTALL_REFERRER` broadcast (see [InstallReferrerBroadcastReceiver])
 * if one has arrived; else neither.
 *
 * A pure decision function on purpose — [InstallReferrerReader.fetch] binds
 * a real Android service with no Robolectric shadow, so the priority logic
 * lives here, separably testable, the same way `classify`/`isTransient`
 * already are in [InstallReferrerReader].
 */
internal object ReferrerFallback {

    /** [status] is what goes in the beacon's `referrer_status` field.
     *  [source] is what goes in `referrer_source` — "google" whenever Play's
     *  own answer is what ends up reported (its authoritative OK/OK_ORGANIC
     *  branch, or the final fallback below when nothing else recovered
     *  anything either), otherwise whichever channel actually supplied the
     *  winning referrer. [broadcastReferrer]/[oemReferrer] are non-null only
     *  when THAT source is the one being used — the Play case still carries
     *  its own richer [InstallReferrerReader.Referrer] (with click/install
     *  timestamps), which the caller applies via `putReferrer` instead. */
    data class Decision(
        val status: String,
        val source: String,
        val broadcastReferrer: String?,
        val oemReferrer: OemReferrer.Referrer?,
    )

    /** [oemSource] is the label ("vivo"/"huawei"/"xiaomi"/"samsung") for
     *  whichever OEM reader was actually attempted — the caller decides that
     *  from [OemDevice.which] before running the read, since it only makes
     *  sense to try the ONE reader matching this device. [oemResult] is null
     *  when no OEM reader ran at all (Play already answered, or this device
     *  isn't one of the four). */
    fun decide(
        playResult: InstallReferrerReader.Result,
        oemSource: String,
        oemResult: OemReferrer.Result?,
        storedBroadcastReferrer: String,
    ): Decision {
        // Play's FINAL word (OK or OK_ORGANIC) must not be second-guessed by an
        // OEM channel or broadcast that could be stale or from a different
        // store's own (possibly wrong) convention. OK_NOT_SET/OK_EMPTY do NOT
        // count as final here even though Play's own Referrer object exists in
        // both cases — see InstallReferrerReader.isAuthoritative's doc comment for why
        // that distinction is load-bearing.
        if (InstallReferrerReader.isAuthoritative(playResult)) {
            return Decision(playResult.status, "google", null, null)
        }
        // The OEM channel outranks the broadcast: it's a synchronous,
        // same-request read carrying its own click/install timestamps — the
        // broadcast is racy (it can arrive after this very beacon already
        // sent, which is exactly what `awaitingBroadcastReferrer` exists to
        // catch) and thinner (a raw string only, no timestamps). Preferring
        // the richer, non-racy source when both could apply is the same
        // principle already applied to Play vs. broadcast above.
        if (oemResult?.referrer != null) {
            return Decision("OK_OEM", oemSource, null, oemResult.referrer)
        }
        if (storedBroadcastReferrer.isNotEmpty()) {
            return Decision("OK_BROADCAST", "broadcast", storedBroadcastReferrer, null)
        }
        return Decision(playResult.status, "google", null, null)
    }
}
