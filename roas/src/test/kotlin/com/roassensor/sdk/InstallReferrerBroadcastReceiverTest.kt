package com.roassensor.sdk

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [InstallReferrerBroadcastReceiver] in isolation — the legacy
 * `INSTALL_REFERRER` broadcast several OEM stores still send. See
 * [ReferrerFallback] for how [Roas] actually uses what this captures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InstallReferrerBroadcastReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val receiver = InstallReferrerBroadcastReceiver()

    @Test
    fun `a referrer extra is decoded and persisted to Storage`() {
        val intent = Intent("com.android.vending.INSTALL_REFERRER")
            .putExtra("referrer", "utm_source%3Dvivo_store%26rs_campaign%3Dsummer_sale")

        receiver.onReceive(app, intent)

        assertEquals("utm_source=vivo_store&rs_campaign=summer_sale", Storage(app).broadcastReferrer)
    }

    @Test
    fun `a missing referrer extra is a no-op`() {
        val intent = Intent("com.android.vending.INSTALL_REFERRER")

        receiver.onReceive(app, intent)

        assertTrue(Storage(app).broadcastReferrer.isEmpty())
    }

    @Test
    fun `an empty referrer extra is a no-op`() {
        val intent = Intent("com.android.vending.INSTALL_REFERRER").putExtra("referrer", "")

        receiver.onReceive(app, intent)

        assertTrue(Storage(app).broadcastReferrer.isEmpty())
    }

    @Test
    fun `a malformed percent-encoding falls back to the raw value rather than losing it`() {
        val intent = Intent("com.android.vending.INSTALL_REFERRER")
            .putExtra("referrer", "rsclid=AbC%")  // trailing % is invalid percent-encoding

        receiver.onReceive(app, intent)

        assertEquals("rsclid=AbC%", Storage(app).broadcastReferrer)
    }
}
