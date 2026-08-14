package com.roassensor.sdk

import android.content.Context
import android.util.Log

/**
 * Reflective caller into the optional `:roas-samsung-referrer` module — see
 * [XiaomiReferrerBridge]'s doc comment for the full reasoning (same shape,
 * same justification, Samsung's `InstallReferrerClient` is the same kind of
 * async/Builder/Listener API as Xiaomi's, so it gets the same treatment).
 */
internal object SamsungReferrerBridge {

    private const val TAG = "RoasSamsungReferrer"
    private const val BRIDGE_CLASS = "com.roassensor.sdk.samsung.SamsungReferrerBridgeImpl"

    fun fetch(context: Context, callback: (OemReferrer.Result) -> Unit) {
        try {
            val bridge = Class.forName(BRIDGE_CLASS)
            val method = bridge.getMethod("fetch", Context::class.java, OemReferrerCallback::class.java)
            method.invoke(
                null,
                context.applicationContext,
                object : OemReferrerCallback {
                    override fun onResult(
                        referrer: String?,
                        clickTimestampSeconds: Long?,
                        installTimestampSeconds: Long?,
                        status: String,
                    ) {
                        callback(toResult(referrer, clickTimestampSeconds, installTimestampSeconds, status))
                    }
                },
            )
        } catch (t: Throwable) {
            Log.d(TAG, "Samsung referrer bridge unavailable: ${t.javaClass.simpleName}")
            callback(OemReferrer.Result(null, "NOT_AVAILABLE"))
        }
    }

    private fun toResult(
        referrer: String?,
        clickTimestampSeconds: Long?,
        installTimestampSeconds: Long?,
        status: String,
    ): OemReferrer.Result {
        if (referrer.isNullOrEmpty()) return OemReferrer.Result(null, status)
        return OemReferrer.Result(
            OemReferrer.Referrer(referrer, clickTimestampSeconds, installTimestampSeconds),
            status,
        )
    }
}
