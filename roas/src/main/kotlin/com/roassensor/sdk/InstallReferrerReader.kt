package com.roassensor.sdk

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails

/**
 * Reads the Google Play **Install Referrer** — the query string ROASSensor
 * stamped into the store URL (`rsclid=…&rs_campaign=…`), handed back verbatim
 * after install. This is the deterministic click→install link that makes
 * Android attribution exact.
 */
internal object InstallReferrerReader {

    private const val TAG = "RoasInstallReferrer"

    /** [clickTimestampSeconds] and [installBeginTimestampSeconds] are the two
     *  endpoints [clickToInstallSeconds] is the difference of. Both are forwarded
     *  because the difference alone is lossy: it cannot be re-windowed against a
     *  different fraud threshold later, and it cannot say WHEN the click happened
     *  — so it can't be lined up against the campaign's own schedule. We had both
     *  values in hand from ReferrerDetails and used to discard them. */
    data class Referrer(
        val referrer: String,
        val clickToInstallSeconds: Long?,
        val clickTimestampSeconds: Long?,
        val installBeginTimestampSeconds: Long?,
    )

    /** [status] is always populated — "OK" on success, else the Play Install
     *  Referrer API's own response-code name (or "EXCEPTION:<message>"). This
     *  used to be silently discarded on failure, which made "why did this
     *  device never get a referrer" undiagnosable: an install-referrer read
     *  can fail per-device for real, device-specific reasons (most commonly
     *  FEATURE_NOT_SUPPORTED — that device's Play Store build predates the
     *  Install Referrer API, which disproportionately hits tablets and
     *  budget/enterprise-provisioned phones that rarely auto-update the Play
     *  Store app) and every one of those reasons needs to be visible, not
     *  swallowed into an indistinguishable "organic" install. */
    data class Result(val referrer: Referrer?, val status: String)

    /** Fetch asynchronously. `referrer` is null when unavailable; `status`
     *  always explains why. */
    fun fetch(context: Context, callback: (Result) -> Unit) {
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                var referrer: Referrer? = null
                var status = responseCodeName(responseCode)
                try {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val details: ReferrerDetails = client.installReferrer
                        val gap = details.installBeginTimestampSeconds -
                            details.referrerClickTimestampSeconds
                        val raw = details.installReferrer ?: ""
                        referrer = Referrer(
                            referrer = raw,
                            clickTimestampSeconds = details.referrerClickTimestampSeconds,
                            installBeginTimestampSeconds = details.installBeginTimestampSeconds,
                            // A gap of only a few seconds — or a NEGATIVE one (the
                            // click fired after the install began) — is the classic
                            // click-injection signal. Forward it raw, including
                            // negatives, so the server can judge; it's the strongest
                            // fraud tell we have.
                            clickToInstallSeconds = gap,
                        )
                        // A successful READ is not a successful ATTRIBUTION. Play
                        // can hand back its own placeholders, and the two mean
                        // opposite things: `organic` is a real answer (nobody
                        // clicked an ad), while `(not set)` means Play had nothing
                        // to give — the referrer was dropped somewhere between the
                        // click and the install. That second case is the Galaxy
                        // M32 / Tab S6 Lite failure, and collapsing both into a
                        // flat "OK" is what made it uncountable and left it to be
                        // found by hand on borrowed hardware.
                        status = classify(raw)
                    } else {
                        Log.w(TAG, "Install Referrer unavailable: $status ($responseCode)")
                    }
                } catch (e: Exception) {
                    status = "EXCEPTION:${e.javaClass.simpleName}"
                    Log.w(TAG, "Install Referrer read threw", e)
                } finally {
                    try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                    callback(Result(referrer, status))
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // No callback here on purpose: reportFirstOpen() never runs, so
                // storage.installReported is never set — the next launch retries
                // this specific transient case naturally.
            }
        })
    }

    /** Refine an OK read into what it actually tells us about attribution.
     *  See the call site for why `organic` and `(not set)` must not be merged. */
    internal fun classify(referrer: String): String {
        val value = referrer.lowercase()
        if (value.isEmpty()) return "OK_EMPTY"
        // Any real signal — ours or a platform's — beats the placeholder check:
        // a referrer can legitimately carry `utm_medium=organic` alongside an
        // rsclid if the campaign was tagged that way.
        if ("rsclid=" in value || "utm_campaign=" in value || "rs_campaign=" in value) return "OK"
        if ("(not set)" in value || "not%20set" in value) return "OK_NOT_SET"
        if ("utm_medium=organic" in value) return "OK_ORGANIC"
        return "OK"
    }

    /**
     * Whether a failed read is worth trying again on a later launch.
     *
     * SERVICE_UNAVAILABLE and SERVICE_DISCONNECTED are the transient ones —
     * Play Services simply wasn't ready to answer yet, which is most likely on
     * exactly the first cold launch after a store install, and more likely
     * still on a slow tablet. FEATURE_NOT_SUPPORTED (Play Store too old),
     * DEVELOPER_ERROR and PERMISSION_ERROR are permanent: retrying them only
     * burns launches.
     */
    fun isTransient(status: String): Boolean = when {
        status.startsWith("OK") -> false
        status == "FEATURE_NOT_SUPPORTED" -> false
        status == "DEVELOPER_ERROR" -> false
        status == "PERMISSION_ERROR" -> false
        else -> true // SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED, EXCEPTION:*, UNKNOWN_*
    }

    private fun responseCodeName(code: Int): String = when (code) {
        InstallReferrerClient.InstallReferrerResponse.OK -> "OK"
        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> "PERMISSION_ERROR"
        else -> "UNKNOWN_$code"
    }
}
