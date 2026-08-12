package com.roassensor.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.net.URLDecoder

/**
 * Catches the legacy `com.android.vending.INSTALL_REFERRER` broadcast — the
 * pre-Play-Install-Referrer-API mechanism several OEM app stores (Huawei
 * AppGallery, Xiaomi GetApps, Vivo App Store, Samsung Galaxy Store, Amazon
 * Appstore) still send for compatibility with older marketing SDKs, even
 * though Google's own Install Referrer API superseded it for Play Store
 * installs years ago.
 *
 * [InstallReferrerReader] only ever asks Play. An app installed from any
 * OTHER store gets `FEATURE_NOT_SUPPORTED` from that API and nothing else —
 * silently indistinguishable from a genuine organic install, on exactly the
 * device class (Vivo, and similar OEM-store-first markets) this SDK has been
 * live-tested on this session. This receiver is the fallback for that case.
 *
 * Declared in this SDK's own AndroidManifest.xml (merges into every
 * consuming app automatically, the same way the Play/Play-Services
 * `<queries>` entries do) — a host app never needs to register anything
 * itself. MUST stay a manifest-declared (not runtime-registered) receiver:
 * the broadcast can arrive before the app has ever been launched, and only a
 * manifest receiver gets a fresh process spun up by the OS to deliver it.
 * For the same reason this class has to be `public`, not `internal` like the
 * rest of this SDK — Android instantiates manifest-declared components by
 * reflection and requires a public no-arg constructor.
 *
 * Deliberately does nothing beyond a synchronous SharedPreferences write — a
 * BroadcastReceiver's process can be killed moments after [onReceive]
 * returns, so anything resembling a network call here would be silently
 * lost most of the time. [Roas] picks the value up on the next real launch,
 * via [ReferrerFallback].
 */
class InstallReferrerBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = try {
            intent.getStringExtra(EXTRA_REFERRER)
        } catch (t: Throwable) {
            null
        }
        if (raw.isNullOrEmpty()) return
        // The legacy broadcast carries the referrer URL-encoded (it started
        // life as a store-listing query parameter) — the modern Play Install
        // Referrer API decodes this for callers itself, so decode here too or
        // the rest of the pipeline (which expects a plain query string) would
        // see literal %3D/%26 instead of =/&.
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (t: Throwable) {
            raw // a malformed encoding is still better than losing the referrer entirely
        }
        Storage(context).broadcastReferrer = decoded
    }

    private companion object {
        const val EXTRA_REFERRER = "referrer"
    }
}
