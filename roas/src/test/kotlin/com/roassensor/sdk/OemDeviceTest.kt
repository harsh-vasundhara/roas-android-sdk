package com.roassensor.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OemDevice]'s pure manufacturer/brand matchers, exercised directly against
 * real `Build.MANUFACTURER`/`Build.BRAND` string pairs — the pure/wrapper
 * split exists specifically so this runs on the plain JVM without Robolectric,
 * the same reason [InstallReferrerReader.classify] is a pure function too.
 */
class OemDeviceTest {

    @Test
    fun `vivo matches on manufacturer, brand, or the iQOO sub-brand`() {
        assertTrue(OemDevice.matchVivo("vivo", "vivo"))
        assertTrue(OemDevice.matchVivo("vivo", "V2130")) // real device's actual brand string
        assertTrue(OemDevice.matchVivo("IQOO", "iqoo"))
    }

    @Test
    fun `huawei matches on manufacturer, brand, or the Honor spin-off brand`() {
        assertTrue(OemDevice.matchHuawei("HUAWEI", "HUAWEI"))
        assertTrue(OemDevice.matchHuawei("HUAWEI", "HRY-LX1T"))
        assertTrue(OemDevice.matchHuawei("HONOR", "HONOR"))
    }

    @Test
    fun `xiaomi matches on manufacturer, brand, or the Redmi POCO sub-brands`() {
        assertTrue(OemDevice.matchXiaomi("Xiaomi", "Xiaomi"))
        assertTrue(OemDevice.matchXiaomi("Xiaomi", "Redmi"))
        assertTrue(OemDevice.matchXiaomi("Xiaomi", "POCO"))
    }

    @Test
    fun `samsung matches on manufacturer or brand`() {
        assertTrue(OemDevice.matchSamsung("samsung", "samsung"))
        assertTrue(OemDevice.matchSamsung("samsung", "SM-M326B"))
    }

    @Test
    fun `unrelated devices never match any OEM`() {
        for (matcher in listOf(
            OemDevice::matchVivo, OemDevice::matchHuawei,
            OemDevice::matchXiaomi, OemDevice::matchSamsung,
        )) {
            assertFalse(matcher("Google", "Pixel 7a"))
            assertFalse(matcher("OnePlus", "OnePlus8Pro"))
            assertFalse(matcher("HMD Global", "Nokia 6.1 Plus"))
            assertFalse(matcher("realme", "RMX3501"))
            assertFalse(matcher("", ""))
        }
    }

    @Test
    fun `a device never matches more than one OEM at once`() {
        // Real device pairs from this session's testing — each must match
        // exactly one matcher, never two, since OemDevice.which() relies on
        // that to pick a single reader.
        val realDevices = listOf(
            "vivo" to "vivo",
            "HUAWEI" to "HUAWEI",
            "Xiaomi" to "Redmi",
            "samsung" to "samsung",
        )
        for ((manufacturer, brand) in realDevices) {
            val matches = listOf(
                OemDevice.matchVivo(manufacturer, brand),
                OemDevice.matchHuawei(manufacturer, brand),
                OemDevice.matchXiaomi(manufacturer, brand),
                OemDevice.matchSamsung(manufacturer, brand),
            ).count { it }
            assertTrue("$manufacturer/$brand matched $matches OEMs, expected exactly 1", matches == 1)
        }
    }
}
