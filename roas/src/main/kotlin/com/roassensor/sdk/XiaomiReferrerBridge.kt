package com.roassensor.sdk

import android.content.Context
import android.util.Log

/**
 * Reflective caller into the optional `:roas-xiaomi-referrer` module, the
 * same "optional vendor capability" convention [DeviceId]/[AppSetId] already
 * use for Play Services — except what's reflected here is a small,
 * first-party, ROASSensor-designed bridge class (`XiaomiReferrerBridgeImpl`),
 * not Xiaomi's own async/Builder/Listener `GetAppsReferrerClient` API
 * directly. Reflecting a stable, single-method surface we control is the
 * same low-risk shape [AppSetId] already reflects (one class, ~one method);
 * reflecting a *callback interface* on a third-party library — which
 * Xiaomi's real client would require — would need a `java.lang.reflect.Proxy`
 * and break silently on any vendor signature change with no compiler to
 * catch it, unacceptable on an attribution-critical path.
 *
 * A host app that hasn't added `com.roassensor:roas-xiaomi-referrer` simply
 * never has this class on the classpath — `Class.forName` throws, this
 * degrades to [OemReferrer].Result("NOT_AVAILABLE"), and every other OEM
 * device is entirely unaffected, since [OemDevice.which] only calls this at
 * all on a device it already matched as Xiaomi.
 */
internal object XiaomiReferrerBridge {

    private const val TAG = "RoasXiaomiReferrer"
    private const val BRIDGE_CLASS = "com.roassensor.sdk.xiaomi.XiaomiReferrerBridgeImpl"

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
            // ClassNotFoundException (module not added — the common case for
            // any non-Xiaomi-targeting app), NoSuchMethodException (should
            // never happen unless the two modules drift out of sync),
            // InvocationTargetException (the bridge impl itself threw) — all
            // of them mean "no Xiaomi referrer available", never a crash.
            Log.d(TAG, "Xiaomi referrer bridge unavailable: ${t.javaClass.simpleName}")
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
