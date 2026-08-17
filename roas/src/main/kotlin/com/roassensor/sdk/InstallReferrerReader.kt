package com.roassensor.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import java.util.concurrent.atomic.AtomicBoolean

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
    /** [clickTimestampServerSeconds]/[installBeginTimestampServerSeconds] are
     *  Google's OWN record of the same two moments, taken on Play's servers
     *  rather than read off this handset's clock.
     *
     *  That distinction is the whole point: the click-injection check
     *  (`ingest.py`, a suspiciously tiny click→install gap) runs on the
     *  client pair above, which a device with a deliberately wrong clock can
     *  set to anything — so the fraud signal was forgeable by the exact
     *  party it exists to catch. These two cannot be, because the attacker's
     *  device never touches them. Sent alongside, never instead of, the
     *  client pair: Play omits the server values on older Play Store builds,
     *  and the client ones are still what a genuine install reports. */
    data class Referrer(
        val referrer: String,
        val clickToInstallSeconds: Long?,
        val clickTimestampSeconds: Long?,
        val installBeginTimestampSeconds: Long?,
        val clickTimestampServerSeconds: Long? = null,
        val installBeginTimestampServerSeconds: Long? = null,
        /** The app version Play recorded at ORIGINAL install. Compared against
         *  the running `app_version` it separates a genuine first install from
         *  an update that is only now reporting because the SDK was just
         *  added — a distinction that otherwise takes guessing at
         *  first_install_at vs last_update_at. */
        val installVersion: String? = null,
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

    /** How long to wait for Play to answer before giving up on this launch.
     *  Confirmed live: a vivo V2130 running Funtouch OS never once reached
     *  [onInstallReferrerSetupFinished] across 8 separate launches over more
     *  than a day — Funtouch's aggressive background-service killing tore
     *  down the Play Services IPC connection before it could answer, and
     *  every launch hit [onInstallReferrerServiceDisconnected] (or nothing at
     *  all) instead. That path used to fire no callback, so
     *  `reportFirstOpen()` never ran and the install was never counted —
     *  silently, forever, since the failure recurs identically on every
     *  future launch too, not just once. */
    private const val TIMEOUT_MS = 5_000L

    /** Fetch asynchronously. `referrer` is null when unavailable; `status`
     *  always explains why. Always calls back exactly once, even when Play
     *  never does — see [TIMEOUT_MS]. */
    fun fetch(context: Context, callback: (Result) -> Unit) {
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        // Both the real listener and the timeout below can fire; this is what
        // keeps the install from being reported twice if Play answers just as
        // the timeout expires.
        val answered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (answered.compareAndSet(false, true)) {
                try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                Log.w(TAG, "Install Referrer read timed out after ${TIMEOUT_MS}ms")
                callback(Result(null, "TIMEOUT"))
            }
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (!answered.compareAndSet(false, true)) return // the timeout already answered
                handler.removeCallbacks(timeoutRunnable)
                var referrer: Referrer? = null
                var status = responseCodeName(responseCode)
                try {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val details: ReferrerDetails = client.installReferrer
                        // An organic install (no real ad click) has nothing to time
                        // FROM — Play reports referrerClickTimestampSeconds as 0 in
                        // that case, not null, and subtracting 0 from a real epoch
                        // install time produced the epoch itself disguised as a
                        // "gap" (a ~56-year click-to-install time, confirmed live on
                        // a vivo V2130 and a Pixel 7a). null here matches how the
                        // backend already treats the two raw timestamps themselves
                        // (ingest.py's _epoch_seconds guards <= 0 the same way) —
                        // this was the one place that guard was missing.
                        val gap = if (details.referrerClickTimestampSeconds > 0) {
                            details.installBeginTimestampSeconds -
                                details.referrerClickTimestampSeconds
                        } else {
                            null
                        }
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
                            // Google's own server-side record of the same two
                            // moments — unforgeable by a tampered device clock,
                            // unlike the pair above. Guarded and defaulted to
                            // null: these getters exist from Install Referrer
                            // library 2.0 but Play returns 0 when its own build
                            // is too old to have recorded them, and a 0 must not
                            // be mistaken for "the epoch".
                            clickTimestampServerSeconds =
                                details.referrerClickTimestampServerSeconds.takeIf { it > 0 },
                            installBeginTimestampServerSeconds =
                                details.installBeginTimestampServerSeconds.takeIf { it > 0 },
                            installVersion = details.installVersion?.takeIf { it.isNotEmpty() },
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
                // Used to do nothing here on the theory that a disconnect is
                // transient and the next launch retries naturally — true for a
                // one-off hiccup, false for a device whose OS tears down the
                // connection on every single launch (see TIMEOUT_MS's doc).
                // The shared timeout above is what actually saves this case
                // now; nothing device-specific belongs here.
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
     * Whether [result] is Play's own FINAL word — worth stopping here rather
     * than falling through to an OEM channel ([ReferrerFallback]) or the
     * legacy broadcast. True for `OK` and `OK_ORGANIC`: Play deliberately
     * told us something, even if that something is "nobody clicked an ad".
     * False for `OK_NOT_SET`/`OK_EMPTY` — Play's own [Referrer] object exists
     * (so a naive `referrer != null` check treats these as final too, which
     * was a real bug here: the OEM fallback was being computed and then
     * silently discarded on exactly the case it exists for) but carries
     * nothing useful, because the referrer was dropped somewhere between the
     * click and the install. That gap is exactly what an OEM channel or the
     * broadcast exists to recover. False for every real failure too
     * (`referrer` is null in that case regardless).
     */
    fun isAuthoritative(result: Result): Boolean =
        result.referrer != null && result.status != "OK_NOT_SET" && result.status != "OK_EMPTY"

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
