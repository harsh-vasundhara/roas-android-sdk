package com.roassensor.sdk

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Reads Huawei AppGallery's own install-referrer channel — see [OemDevice]'s
 * doc comment for why an OEM-native channel exists at all alongside Google's.
 *
 * Huawei exposes this as a plain `ContentProvider` query (not a bound
 * service, not `ContentResolver.call()` like Vivo's) — synchronous local IPC,
 * no timeout needed for the same reason [VivoReferrerReader] doesn't need
 * one: it answers immediately, or throws/returns null immediately when the
 * provider isn't present (any non-Huawei device) — caught below exactly like
 * [VivoReferrerReader] catches its own absent-provider case.
 */
internal object HuaweiReferrerReader {

    private const val TAG = "RoasHuaweiReferrer"
    private const val AUTHORITY = "com.huawei.appmarket.commondata"
    private val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/item/5")

    // Fixed column positions in the returned cursor's single row.
    private const val COLUMN_REFERRER = 0
    private const val COLUMN_CLICK_TIME = 1
    private const val COLUMN_INSTALL_TIME = 2

    /** Synchronous under the hood; callback-shaped to match every other
     *  reader's call site in [Roas.reportFirstOpen] uniformly. Always calls
     *  back exactly once. */
    fun fetch(context: Context, callback: (OemReferrer.Result) -> Unit) {
        callback(read(context))
    }

    private fun read(context: Context): OemReferrer.Result {
        // Resolve FIRST, so "AppGallery isn't on this device" (NOT_AVAILABLE)
        // stays distinguishable from "AppGallery is here but has no referrer
        // for this install" (OK_EMPTY). Collapsing those two made a real vivo
        // finding unreadable — see VivoReferrerReader.read's comment — and
        // the same trap applies verbatim here.
        //
        // Requires the matching <provider> entry in this SDK's manifest
        // <queries>: on API 30+ package visibility hides the provider
        // otherwise, and this returns null even on a Huawei device with
        // AppGallery installed. See AndroidManifest.xml.
        if (context.packageManager.resolveContentProvider(AUTHORITY, 0) == null) {
            Log.d(TAG, "Huawei referrer unavailable: provider not present")
            return OemReferrer.Result(null, "NOT_AVAILABLE")
        }
        return try {
            context.contentResolver.query(
                CONTENT_URI,
                null,
                null,
                arrayOf(context.packageName),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    OemReferrer.Result(null, "OK_EMPTY")
                } else {
                    val raw = cursor.getString(COLUMN_REFERRER)
                    if (raw.isNullOrEmpty()) {
                        OemReferrer.Result(null, "OK_EMPTY")
                    } else {
                        val click = cursor.getString(COLUMN_CLICK_TIME)?.toLongOrNull()?.takeIf { it > 0 }
                        val install = cursor.getString(COLUMN_INSTALL_TIME)?.toLongOrNull()?.takeIf { it > 0 }
                        OemReferrer.Result(
                            OemReferrer.Referrer(raw, click, install),
                            "OK",
                        )
                    }
                }
            // The provider resolved a moment ago, so a null cursor is it
            // declining to answer for this app, not an absent store.
            } ?: OemReferrer.Result(null, "OK_EMPTY")
        } catch (e: Exception) {
            Log.d(TAG, "Huawei referrer query failed: ${e.javaClass.simpleName}")
            OemReferrer.Result(null, "OK_EMPTY")
        }
    }
}
