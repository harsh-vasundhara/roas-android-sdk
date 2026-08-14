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
    private val CONTENT_URI: Uri = Uri.parse("content://com.huawei.appmarket.commondata/item/5")

    // Fixed column positions in the returned cursor's single row.
    private const val COLUMN_REFERRER = 0
    private const val COLUMN_CLICK_TIME = 1
    private const val COLUMN_INSTALL_TIME = 2

    /** Synchronous under the hood; callback-shaped to match every other
     *  reader's call site in [Roas.reportFirstOpen] uniformly. Always calls
     *  back exactly once. */
    fun fetch(context: Context, callback: (OemReferrer.Result) -> Unit) {
        val result = try {
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
            } ?: OemReferrer.Result(null, "NOT_AVAILABLE")
        } catch (e: Exception) {
            // The overwhelmingly common outcome on any non-Huawei device: the
            // provider doesn't exist, and querying an absent authority throws
            // rather than returning null on some Android versions.
            Log.d(TAG, "Huawei referrer unavailable: ${e.javaClass.simpleName}")
            OemReferrer.Result(null, "NOT_AVAILABLE")
        }
        callback(result)
    }
}
