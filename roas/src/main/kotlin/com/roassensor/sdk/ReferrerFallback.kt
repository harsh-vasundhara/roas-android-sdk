package com.roassensor.sdk

/**
 * Which referrer signal an install beacon should carry: the Google Play
 * Install Referrer API's own result if it found one, else the legacy
 * `INSTALL_REFERRER` broadcast (see [InstallReferrerBroadcastReceiver]) if
 * one has arrived, else neither.
 *
 * A pure decision function on purpose — [InstallReferrerReader.fetch] binds
 * a real Android service with no Robolectric shadow, so the priority logic
 * lives here, separably testable, the same way `classify`/`isTransient`
 * already are in [InstallReferrerReader].
 */
internal object ReferrerFallback {

    /** [status] is what goes in the beacon's `referrer_status` field.
     *  [broadcastReferrer] is non-null only when the legacy broadcast is the
     *  one being used — the Play case still carries its own richer
     *  [InstallReferrerReader.Referrer] (with click/install timestamps),
     *  which the caller applies via `putReferrer` instead. */
    data class Decision(val status: String, val broadcastReferrer: String?)

    fun decide(playResult: InstallReferrerReader.Result, storedBroadcastReferrer: String): Decision {
        // Play answered with something — even OK_ORGANIC/OK_EMPTY is Play's own
        // definitive answer and must not be second-guessed by a broadcast that
        // could be stale or from a different store's own (possibly wrong)
        // convention. Only Play's total silence opens the door to the fallback.
        if (playResult.referrer != null) {
            return Decision(playResult.status, null)
        }
        if (storedBroadcastReferrer.isNotEmpty()) {
            return Decision("OK_BROADCAST", storedBroadcastReferrer)
        }
        return Decision(playResult.status, null)
    }
}
