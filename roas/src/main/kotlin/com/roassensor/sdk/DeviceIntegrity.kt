package com.roassensor.sdk

import android.os.Build
import java.io.File

/**
 * Emulator and root signals for the install beacon.
 *
 * ## What this reports, and what it deliberately does not
 *
 * It sends the individual signals that fired — never a verdict. The
 * classification happens on the server (`services/integrity.py`), and the reason
 * is the same one that governs `require_signed_beacons`: an app build lives on
 * real handsets for years. Bake a threshold in here and the day a new emulator
 * starts evading one of these checks, the fix ships at the speed of app-store
 * review and user updates — which is to say never, for much of the fleet.
 * Sending evidence instead of a conclusion means the rule can be retuned on a
 * deploy, and old installs can be reinterpreted.
 *
 * ## Honest ceiling
 *
 * This is a self-report from a device that may be the adversary. Anyone who can
 * root a phone can patch this class to return nothing, and the commercial
 * emulators used by real install farms already spoof the `Build` fields half of
 * these checks read. It catches the careless and the incidental — which in
 * practice is mostly the customer's own QA and CI, and separating THOSE out is
 * most of the value here. It is not proof of anything and must never be
 * described as such.
 *
 * Every check is individually guarded: a `SecurityException` reading a path we
 * are not allowed to see must cost us the signal, never the install.
 */
internal object DeviceIntegrity {

    /** Emulator-specific kernel devices. Present on nothing real. */
    private val QEMU_FILES = arrayOf(
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
    )

    /** `su` on any of the paths a rooted device actually puts it. */
    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
    )

    private val MAGISK_FILES = arrayOf(
        "/sbin/.magisk",
        "/cache/.disable_magisk",
        "/init.magisk.rc",
        "/data/adb/magisk",
    )

    private fun exists(path: String): Boolean = try {
        File(path).exists()
    } catch (t: Throwable) {
        // SecurityException on a path this process may not stat. "Can't tell" is
        // not "present" — a signal we cannot read must not be reported as fired.
        false
    }

    private fun anyExists(paths: Array<String>): Boolean = paths.any { exists(it) }

    /**
     * The signals that fired, as short stable names the server knows. Empty on a
     * device that looks ordinary — which is the overwhelming majority, so the
     * field is usually absent from the beacon entirely.
     */
    fun signals(): List<String> {
        val found = mutableListOf<String>()

        // ── Emulator ────────────────────────────────────────────────────────
        // Strong: names emulator hardware directly.
        val hardware = safe { Build.HARDWARE }.lowercase()
        if (hardware.contains("goldfish") || hardware.contains("ranchu") ||
            hardware.contains("vbox") || hardware.contains("nox")
        ) {
            found.add("hardware")
        }
        if (anyExists(QEMU_FILES)) found.add("qemu_files")

        // Weak: individually inconclusive — a cheap real handset can ship a
        // generic fingerprint or an unset model — so the server requires two.
        val fingerprint = safe { Build.FINGERPRINT }.lowercase()
        if (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown") ||
            fingerprint.contains("vbox") || fingerprint.contains("emulator")
        ) {
            found.add("fingerprint")
        }
        val model = safe { Build.MODEL }.lowercase()
        if (model.contains("google_sdk") || model.contains("emulator") ||
            model.contains("android sdk built for")
        ) {
            found.add("model")
        }
        val manufacturer = safe { Build.MANUFACTURER }.lowercase()
        if (manufacturer.contains("genymotion") || manufacturer.contains("unknown")) {
            found.add("manufacturer")
        }
        val brand = safe { Build.BRAND }.lowercase()
        val device = safe { Build.DEVICE }.lowercase()
        if (brand.startsWith("generic") && device.startsWith("generic")) found.add("brand")
        val product = safe { Build.PRODUCT }.lowercase()
        if (product == "google_sdk" || product == "sdk" || product.startsWith("sdk_") ||
            product.startsWith("vbox")
        ) {
            found.add("product")
        }
        // NOT included: an x86 ABI on its own. It used to be a reliable emulator
        // tell and is now simply wrong — x86 Chromebooks and Windows Subsystem
        // for Android run real users' real installs.

        // ── Root ────────────────────────────────────────────────────────────
        if (anyExists(SU_PATHS)) found.add("su_binary")
        if (anyExists(MAGISK_FILES)) found.add("magisk_files")
        // Famously noisy on its own: plenty of legitimate custom ROMs and some
        // budget OEM builds ship debug-signed, so the server treats it as weak.
        if (safe { Build.TAGS }.contains("test-keys")) found.add("test_keys")
        if (isSystemWritable()) found.add("system_writable")

        return found
    }

    /** `/system` mounted read-write is not something a stock device does. */
    private fun isSystemWritable(): Boolean = try {
        File("/system").canWrite() || File("/system/bin").canWrite()
    } catch (t: Throwable) {
        false
    }

    /** Build fields are non-null in practice but not in signature, and a broken
     *  OEM build has been known to throw. */
    private inline fun safe(block: () -> String?): String = try {
        block() ?: ""
    } catch (t: Throwable) {
        ""
    }
}
