package com.roassensor.sdk

import android.content.Context

/**
 * The Google Advertising ID (GAID), read reflectively so the SDK does not
 * hard-depend on Play Services — an app without it degrades to referrer-only
 * attribution rather than failing to compile or crashing.
 *
 * MUST be called off the main thread (the caller runs it on the Transport
 * executor): `getAdvertisingIdInfo` performs a blocking bind to Play Services.
 */
internal object DeviceId {

    /** `advertisingId` or null when Play Services is absent, the AD_ID permission
     *  is denied, or the user enabled "limit ad tracking" (opt-out is respected —
     *  we never send an id the user asked us not to use). */
    fun advertisingId(context: Context): String? {
        return try {
            val clientClass =
                Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            val info = clientClass
                .getMethod("getAdvertisingIdInfo", Context::class.java)
                .invoke(null, context.applicationContext)
            val limited =
                info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean ?: false
            if (limited) return null
            info.javaClass.getMethod("getId").invoke(info) as? String
        } catch (t: Throwable) {
            null
        }
    }
}
