package com.roassensor.sdk

import android.content.Context
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

    data class Referrer(val referrer: String, val clickToInstallSeconds: Long?)

    /** Fetch asynchronously; `callback` receives the referrer or null (Play Store
     *  unavailable, or this device came from somewhere without one). */
    fun fetch(context: Context, callback: (Referrer?) -> Unit) {
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                var result: Referrer? = null
                try {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val details: ReferrerDetails = client.installReferrer
                        val gap = details.installBeginTimestampSeconds -
                            details.referrerClickTimestampSeconds
                        result = Referrer(
                            referrer = details.installReferrer ?: "",
                            // A gap of only a few seconds — or a NEGATIVE one (the
                            // click fired after the install began) — is the classic
                            // click-injection signal. Forward it raw, including
                            // negatives, so the server can judge; it's the strongest
                            // fraud tell we have.
                            clickToInstallSeconds = gap,
                        )
                    }
                } catch (e: Exception) {
                    result = null
                } finally {
                    try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                    callback(result)
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Transient — the next launch retries. Report nothing this time.
            }
        })
    }
}
