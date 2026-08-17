package com.roassensor.sdk

import android.os.Build

/**
 * Which OEM-native install-referrer channel (if any) this device might answer
 * through, alongside Google's own. Vivo/Huawei/Xiaomi/Samsung each track
 * installs on their own skinned Android builds for their own analytics and
 * expose it via their own API — independent of, and often more reliable
 * than, Google's Play Install Referrer API on those builds (confirmed live
 * this session: Google's channel answered `OK_NOT_SET` on real Xiaomi,
 * Samsung, and Huawei devices seconds after a real, matched ad click).
 *
 * Split into pure matchers (testable with plain strings, no Robolectric) and
 * a thin [which] wrapper that reads the real [Build] fields — the same
 * "pure core, thin Android wrapper" shape [InstallReferrerReader.classify]
 * already uses, for the same reason: `Build.MANUFACTURER`/`Build.BRAND`
 * aren't fakeable in a plain JUnit test without it.
 *
 * **Matching a manufacturer here does NOT mean that OEM's store is present**,
 * and the difference is not academic: confirmed live on a real vivo V2142
 * (`ro.product.manufacturer=vivo`, 82 vivo system packages) that carried **no
 * `com.vivo.appstore` at all** — not even disabled or uninstalled-for-user —
 * so [VivoReferrerReader] correctly answered `NOT_AVAILABLE` and the beacon
 * fell through to Google's own answer exactly as designed. A device ships,
 * or a user removes, an OEM store independently of who built the handset.
 *
 * So this is a *routing* decision ("which ONE reader is even worth asking"),
 * never a promise that the reader will find anything. Each reader reports its
 * own `NOT_AVAILABLE` honestly rather than being pre-gated here on a package
 * check, because the reader's own read is the authoritative answer to "can
 * this channel actually help?" — a `<queries>` package probe would just be a
 * second, staler way to ask the same question.
 */
internal object OemDevice {

    enum class Source { VIVO, HUAWEI, XIAOMI, SAMSUNG, NONE }

    internal fun matchVivo(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m == "vivo" || b == "vivo" || b == "iqoo"
    }

    /** Honor spun off from Huawei in 2020 but many in-market devices still
     *  carry Huawei's AppGallery and answer the same content provider. */
    internal fun matchHuawei(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m == "huawei" || b == "huawei" || b == "honor"
    }

    /** Redmi and POCO both ship as Xiaomi's own sub-brands with GetApps
     *  preinstalled; `Build.MANUFACTURER` stays "Xiaomi" on both, but
     *  `Build.BRAND` can read "Redmi"/"POCO" — check both fields. */
    internal fun matchXiaomi(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m == "xiaomi" || b == "xiaomi" || b == "redmi" || b == "poco"
    }

    internal fun matchSamsung(manufacturer: String, brand: String): Boolean {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return m == "samsung" || b == "samsung"
    }

    /** Real device read. First match wins — a device only ever matches one of
     *  these in practice (they're mutually exclusive OEM identities), so
     *  order has no attribution consequence, only readability. */
    fun which(): Source {
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        return when {
            matchVivo(manufacturer, brand) -> Source.VIVO
            matchHuawei(manufacturer, brand) -> Source.HUAWEI
            matchXiaomi(manufacturer, brand) -> Source.XIAOMI
            matchSamsung(manufacturer, brand) -> Source.SAMSUNG
            else -> Source.NONE
        }
    }
}
