package com.roassensor.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * Coarse device location — **only when the host app already holds a location
 * permission for its own reasons.**
 *
 * ## This SDK never asks for location, and must never start
 *
 * There is no location permission in this SDK's `AndroidManifest.xml`, and
 * adding one would be a serious mistake rather than a feature: library
 * manifests MERGE into the host app's, so declaring it here would silently add
 * a location permission to *every* app that depends on this SDK — apps whose
 * developers never asked for it, whose Play listing would suddenly claim it,
 * and whose users would see it. An attribution SDK must not make that decision
 * on a customer's behalf.
 *
 * So this reads location only where it is already free: the host app declared
 * the permission and the user already granted it, for the app's own features.
 * On every other app [read] returns null and nothing happens — no prompt, no
 * policy exposure, no behaviour change.
 *
 * ## Why last-known, and why rounded
 *
 * [LocationManager.getLastKnownLocation] returns a cached fix. It never wakes
 * the GPS, costs no battery, and takes no time — appropriate for a value that
 * decorates an install beacon. Requesting a live fix would put a hardware
 * radio on the critical path of reporting an install, for a field that is a
 * nice-to-have.
 *
 * The result is rounded to two decimal places (~1.1 km) before it leaves the
 * device. A geo dashboard renders cities; shipping metre-accurate coordinates
 * would collect a far more sensitive value than the use case needs, and the
 * rounding happens HERE rather than server-side so the precise value never
 * travels at all.
 *
 * ## This is a bonus, never the primary geo
 *
 * Coverage is whatever fraction of a customer's users happen to use an app
 * that already has location — a self-selected minority. `services/geo.py`
 * resolves country/region/city from the IP for 100% of traffic with no
 * permission at all, and that is what a dashboard should be built on. This
 * adds precision where it is already available for free.
 */
internal object DeviceLocation {

    private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"

    /** How stale a cached fix may be and still be worth reporting. A week-old
     *  position describes a trip, not an install. */
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /** `Pair(lat, lon)` rounded to ~1.1km, or null when the host app holds no
     *  location permission, nothing is cached, or the fix is too old. */
    fun read(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        return try {
            val manager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return null
            // Newest fix across providers. Network is usually both fresher and
            // coarser than GPS, which suits this perfectly.
            val best = manager.enabledProviders()
                .mapNotNull { provider ->
                    @Suppress("MissingPermission") // guarded by hasPermission above
                    try {
                        manager.getLastKnownLocation(provider)
                    } catch (t: Throwable) {
                        null
                    }
                }
                .maxByOrNull { it.time }
                ?: return null
            if (System.currentTimeMillis() - best.time > MAX_AGE_MS) return null
            Pair(round2(best.latitude), round2(best.longitude))
        } catch (t: Throwable) {
            null
        }
    }

    /** True only if the HOST app declared a location permission AND the user
     *  granted it. A permission this SDK never declares can never be granted
     *  to it alone, so this is entirely the host app's posture. */
    private fun hasPermission(context: Context): Boolean = try {
        // Context.checkSelfPermission is API 23+, and this SDK's minSdk is 21.
        // Below that a declared permission is granted at install time, but a
        // pre-Marshmallow handset is not a population worth reading location
        // from, so treat it as absent rather than adding a compat path.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            false
        } else {
            context.checkSelfPermission(FINE) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED
        }
    } catch (t: Throwable) {
        false
    }

    private fun LocationManager.enabledProviders(): List<String> = try {
        // enabled-only: a disabled provider's cached fix is stale by
        // definition, and asking for it logs noisily on some builds.
        getProviders(true).ifEmpty { listOf(LocationManager.NETWORK_PROVIDER) }
    } catch (t: Throwable) {
        emptyList()
    }

    /** Two decimals ≈ 1.1 km. Deliberately lossy — see the class doc. */
    private fun round2(value: Double): Double = (value * 100.0).roundToInt() / 100.0
}
