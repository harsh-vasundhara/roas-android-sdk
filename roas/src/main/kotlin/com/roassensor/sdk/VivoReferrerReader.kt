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
 * IPC) or the provider isn't there at all (any non-Vivo device — by far the
 * common case), in which case `call()` returns null or throws immediately.
 */
internal object VivoReferrerReader {

    private const val TAG = "RoasVivoReferrer"
    private val AUTHORITY_URI: Uri = Uri.parse("content://com.vivo.appstore.provider.referrer")
    private const val METHOD = "read_referrer"

    /** Synchronous under the hood; callback-shaped to match every other
     *  reader's call site in [Roas.reportFirstOpen] uniformly. Always calls
     *  back exactly once. */
    fun fetch(context: Context, callback: (OemReferrer.Result) -> Unit) {
        val result = try {
            val bundle: Bundle? = context.contentResolver.call(AUTHORITY_URI, METHOD, null, null)
            if (bundle == null) {
                OemReferrer.Result(null, "NOT_AVAILABLE")
            } else {
                val raw = bundle.getString("install_referrer")
                if (raw.isNullOrEmpty()) {
                    OemReferrer.Result(null, "OK_EMPTY")
                } else {
                    val click = bundle.getLong("referrer_click_timestamp_seconds", 0L).takeIf { it > 0 }
                    val install = bundle.getLong("download_begin_timestamp_seconds", 0L).takeIf { it > 0 }
                    OemReferrer.Result(
                        OemReferrer.Referrer(raw, click, install),
                        "OK",
                    )
                }
            }
        } catch (e: Exception) {
            // The overwhelmingly common outcome on any non-Vivo device: the
            // provider doesn't exist, and querying an absent authority throws
            // rather than returning null on some Android versions.
            Log.d(TAG, "Vivo referrer unavailable: ${e.javaClass.simpleName}")
            OemReferrer.Result(null, "NOT_AVAILABLE")
        }
        callback(result)
    }
}
