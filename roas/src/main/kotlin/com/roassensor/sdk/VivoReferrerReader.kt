package com.roassensor.sdk

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Reads Vivo App Store's own install-referrer channel — independent of, and
 * on real Vivo hardware sometimes more reliable than, Google's Play Install
 * Referrer API (see [OemDevice]'s doc comment for why this exists at all).
 *
 * Vivo exposes this via a `ContentResolver.call()`, not a bound service —
 * simpler than Google's/Xiaomi's/Samsung's client-connection lifecycle,
 * synchronous, no timeout needed: it either answers immediately (fast local
 * IPC) or the provider isn't there at all, in which case `call()` returns
 * null or throws immediately.
 */
internal object VivoReferrerReader {

    private const val TAG = "RoasVivoReferrer"
    private const val METHOD = "read_referrer"

    /**
     * Every authority the Vivo store's referrer provider is known to live
     * behind, tried in order until one resolves.
     *
     * **This is a list, not a constant, because the Vivo store's PACKAGE NAME
     * varies by build, and the authority is derived from it.** Found the hard
     * way: two Indian-market handsets (V2130, V2142) were initially written
     * off as "no Vivo store installed" because neither had a
     * `com.vivo.appstore` package and the single hardcoded
     * `com.vivo.appstore.provider.referrer` authority threw
     * "Could not find provider". Both in fact ship the store as
     * **`com.vivo.apprecommend`** — same app (its launcher activity is still
     * `com.vivo.appstore.activity.LaunchActivity`, and it is what handles
     * `market://` links), exposing the same provider class
     * (`com.vivo.appstore.referrer.ReferrerProvider`) under the
     * package-derived authority `com.vivo.apprecommend.provider.referrer`,
     * which answers a `read_referrer` call correctly.
     *
     * So a single hardcoded authority silently reported NOT_AVAILABLE on
     * devices where real referrer data was actually reachable. Treat this
     * list as a living one: a new Vivo build shipping the store under a third
     * package name needs its authority added here, and the symptom will again
     * be "Vivo devices never attribute" rather than any error.
     */
    private val AUTHORITIES = listOf(
        // The upstream/China-build name, and what Adjust's own Vivo plugin uses.
        "com.vivo.appstore.provider.referrer",
        // Confirmed live on Indian-market V2130 and V2142 (2130i / 2127i).
        "com.vivo.apprecommend.provider.referrer",
    )

    /** Synchronous under the hood; callback-shaped to match every other
     *  reader's call site in [Roas.reportFirstOpen] uniformly. Always calls
     *  back exactly once. */
    fun fetch(context: Context, callback: (OemReferrer.Result) -> Unit) {
        callback(read(context))
    }

    private fun read(context: Context): OemReferrer.Result {
        // `NOT_AVAILABLE` must mean "this device has no Vivo store at all",
        // and nothing weaker. A store that IS installed but has no referrer
        // for this particular install is `OK_EMPTY` — a completely different
        // fact, and the same distinction the Google path already draws
        // between FEATURE_NOT_SUPPORTED and OK_NOT_SET. Conflating them is
        // what makes "why does this device never attribute?" unanswerable
        // from the backend, which is the whole reason referrer_status and
        // referrer_source exist.
        //
        // Confirmed live on a V2130: the store is present (as
        // com.vivo.apprecommend) and its provider resolves, but `call`
        // returns a null Bundle for a SIDELOADED app — the store only holds
        // referrer records for installs it actually performed. That must read
        // as OK_EMPTY, not as an absent store.
        var storeFound = false
        for (authority in AUTHORITIES) {
            // Requires the matching <provider> entry in this SDK's manifest
            // <queries> — without it this returns null on API 30+ even when
            // the provider is right there. See AndroidManifest.xml.
            if (context.packageManager.resolveContentProvider(authority, 0) == null) continue
            storeFound = true
            val bundle: Bundle? = try {
                context.contentResolver.call(Uri.parse("content://$authority"), METHOD, null, null)
            } catch (e: Exception) {
                Log.d(TAG, "Vivo referrer call failed on $authority: ${e.javaClass.simpleName}")
                null
            }
            val raw = bundle?.getString("install_referrer")
            if (raw.isNullOrEmpty()) continue // this store has nothing for us; try any other name
            val click = bundle.getLong("referrer_click_timestamp_seconds", 0L).takeIf { it > 0 }
            val install = bundle.getLong("download_begin_timestamp_seconds", 0L).takeIf { it > 0 }
            return OemReferrer.Result(OemReferrer.Referrer(raw, click, install), "OK")
        }
        return if (storeFound) {
            OemReferrer.Result(null, "OK_EMPTY")
        } else {
            Log.d(TAG, "Vivo referrer unavailable: no known authority resolved")
            OemReferrer.Result(null, "NOT_AVAILABLE")
        }
    }
}
