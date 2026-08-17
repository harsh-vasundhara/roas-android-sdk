package com.roassensor.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * The device context stamped onto every install beacon.
 *
 * This exists because of a real, expensive gap: an install that fails to
 * attribute used to arrive carrying nothing but `os=Android`, so "which
 * devices are we losing?" could only be answered by borrowing physical
 * handsets. The Play catalog is ~18,600 models — a QA rack of eight can
 * never cover it, and the failures cluster exactly where a small rack is
 * thinnest (tablets are ~34% of the catalog; the Galaxy M32 / Tab S6 Lite
 * referrer failures were found by hand, one device at a time).
 *
 * Every value here is best-effort and individually guarded: a missing one
 * must degrade to an empty field, never take down the install report.
 */
internal object DeviceInfo {

    /** This SDK's own version, sent on every install so a bad row can be traced
     *  to the build that produced it. Bump with the version in build.gradle.kts. */
    const val SDK_VERSION = "0.1.6"

    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"

    /** A tablet is the ≥600dp smallest-width bucket — the same threshold the
     *  platform itself uses for `sw600dp` resources.
     *
     *  Worth stating plainly: this used to be the constant string "mobile",
     *  and `ingest.py` lets a beacon's `device_type` override its own
     *  User-Agent parse, so **every Android tablet in the database was
     *  recorded as a phone**. The form factor most likely to break
     *  attribution was the one form factor reporting made invisible. */
    fun deviceType(context: Context): String = try {
        if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "mobile"
    } catch (t: Throwable) {
        "mobile"
    }

    /**
     * The Google Play Store app's own version.
     *
     * This — not the Android OS version — is what actually gates the Install
     * Referrer API (it needs a Play Store of roughly 8.3.73+). It is why a
     * five-year-old tablet on the same Android release as a working phone
     * returns FEATURE_NOT_SUPPORTED: the tablet's Play Store is stale, because
     * tablets and enterprise-provisioned devices rarely auto-update it. Without
     * this field the SDK reports the symptom and withholds the cause.
     *
     * Reading it needs the `<queries>` entry in the manifest — Android 11+
     * package visibility hides other packages otherwise, and this throws
     * NameNotFoundException rather than returning null.
     */
    fun playStoreVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(PLAY_STORE_PACKAGE, 0).versionName ?: ""
    } catch (t: Throwable) {
        // NameNotFoundException also means Play is genuinely absent (an
        // Amazon/Huawei/AOSP build), which is itself the answer to "why did
        // this install never carry a referrer" — so say so rather than blank.
        ""
    }

    /**
     * Google Play **Services** (GMS) version — a different component from the
     * Play Store app above, answering a different question.
     *
     * `playStoreVersion` explains a missing install referrer. This explains a
     * missing GAID: `AdvertisingIdClient` binds to Play Services, so a device
     * with a perfectly current Play Store but stale/absent GMS reports a blank
     * `device_id` for a reason nothing else in the payload can distinguish from
     * a user opt-out. Reading it needs its own `<queries>` entry, same as Play.
     */
    fun playServicesVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(PLAY_SERVICES_PACKAGE, 0).versionName ?: ""
    } catch (t: Throwable) {
        // Genuinely absent (an AOSP/Huawei build) — itself the answer to "why is
        // there no ad id on this device", so blank is reported, not an error.
        ""
    }

    /** Which store actually installed us — `com.android.vending` for Play,
     *  something else for Galaxy Store / a sideload / an `adb install`. An
     *  install that did not come from Play can never have a Play referrer, and
     *  that is a different fact from Play failing to hand one over. */
    @Suppress("DEPRECATION")
    fun installerPackage(context: Context): String = try {
        val pm = context.packageManager
        val self = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(self).installingPackageName ?: ""
        } else {
            pm.getInstallerPackageName(self) ?: ""
        }
    } catch (t: Throwable) {
        ""
    }

    private fun screen(context: Context): String = try {
        val metrics = context.resources.displayMetrics
        "${metrics.widthPixels}x${metrics.heightPixels}"
    } catch (t: Throwable) {
        ""
    }

    private fun language(): String = try {
        Locale.getDefault().toLanguageTag()
    } catch (t: Throwable) {
        ""
    }

    private fun timezone(): String = try {
        TimeZone.getDefault().id ?: ""
    } catch (t: Throwable) {
        ""
    }

    /**
     * Add every device field to [body], in place. Nothing here can throw: each
     * getter already swallows its own failure, and a blank field is always a
     * better outcome than a lost install.
     */
    fun describe(context: Context, body: JSONObject): JSONObject {
        body.put("sdk_version", SDK_VERSION)
        body.put("device_type", deviceType(context))
        body.put("os_version", Build.VERSION.RELEASE ?: "")
        body.put("api_level", Build.VERSION.SDK_INT)
        body.put("device_manufacturer", Build.MANUFACTURER ?: "")
        // Build.MODEL is the marketing-ish name ("SM-P613"); the Play device
        // catalog keys on it, so it joins straight against a devices.csv export.
        body.put("device_model", Build.MODEL ?: "")
        body.put("store_version", playStoreVersion(context))
        body.put("play_services_version", playServicesVersion(context))
        // Emulator / root signals — the raw list, never a verdict. Omitted
        // entirely on an ordinary device, which is nearly all of them, so this
        // adds nothing to the usual beacon. See DeviceIntegrity for the ceiling.
        DeviceIntegrity.signals(context)
            .takeIf { it.isNotEmpty() }
            ?.let { body.put("integrity_signals", org.json.JSONArray(it)) }
        body.put("installer_package", installerPackage(context))
        screen(context).takeIf { it.isNotEmpty() }?.let { body.put("screen", it) }
        language().takeIf { it.isNotEmpty() }?.let { body.put("language", it) }
        timezone().takeIf { it.isNotEmpty() }?.let { body.put("timezone", it) }
        // The OS's own record of when this app was installed and last updated
        // — read from PackageManager, outside the app's data dir, so no
        // backup/restore or data-clone can forge it (the same property
        // Roas.resetIfDataWasResurrected already relies on). It gives the
        // server a true install moment independent of when the beacon
        // arrived, and `first_install_at != last_update_at` is how a genuine
        // first install is told apart from an update reporting for the first
        // time after the SDK was added.
        installTimes(context).let { (first, lastUpdate) ->
            first?.let { body.put("first_install_timestamp", it) }
            lastUpdate?.let { body.put("last_update_timestamp", it) }
        }
        // How this device is on the network right now. `is_vpn` is the
        // load-bearing one: the backend's deferred same-IP match is the only
        // place an install with no referrer can still be attributed, and it
        // compares the install's IP to the click's — which a VPN silently
        // invalidates, producing a wrong match or a missed one with no way to
        // tell after the fact. Costs no new permission: ACCESS_NETWORK_STATE
        // is already held for delivery.
        network(context).let { (transport, vpn) ->
            transport.takeIf { it.isNotEmpty() }?.let { body.put("network_type", it) }
            vpn?.let { body.put("is_vpn", it) }
        }
        return body
    }

    /** `first_install` / `last_update`, epoch **seconds** (PackageManager
     *  reports millis; the server's `_epoch_seconds` guard deliberately
     *  rejects millis-shaped values, so convert here rather than sending a
     *  unit the backend is built to refuse). */
    private fun installTimes(context: Context): Pair<Long?, Long?> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        Pair(
            (info.firstInstallTime / 1000L).takeIf { it > 0 },
            (info.lastUpdateTime / 1000L).takeIf { it > 0 },
        )
    } catch (t: Throwable) {
        Pair(null, null)
    }

    /** `Pair(transport, isVpn)` — e.g. `("wifi", false)`. Both null/blank when
     *  the state cannot be read, which is just an absent field. */
    private fun network(context: Context): Pair<String, Boolean?> {
        // activeNetwork and NET_CAPABILITY_NOT_VPN are API 23+; this SDK's
        // minSdk is 21. The catch below would swallow the NoSuchMethodError
        // anyway, but checking says so on purpose rather than leaving a real
        // API mismatch to be absorbed by a broad catch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Pair("", null)
        return try {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            if (capabilities == null) {
                Pair("", null)
            } else {
                val transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    // A handset on wired ethernet is worth seeing: it is far
                    // more often an emulator or a device farm than a phone.
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                // NOT_VPN is absent exactly when a VPN IS in play.
                Pair(
                    transport,
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                )
            }
        } catch (t: Throwable) {
            Pair("", null)
        }
    }
}
