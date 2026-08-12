package com.roassensor.sdk

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Storage] in isolation, focused on [Storage.resetForNewInstall] and
 * [Storage.firstInstallTime] — added after a live Vivo device test showed an
 * on-device uninstall+reinstall handing the SDK back its OLD vid and
 * installReported=true, because the SharedPreferences file survived even
 * though the OS genuinely reinstalled the package. See [RoasTest]'s
 * "OS-reinstall detection" group for how [Roas] uses these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageTest {

    private lateinit var storage: Storage

    @Before
    fun setUp() {
        storage = Storage(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `firstInstallTime defaults to 0 and round-trips once set`() {
        assertEquals(0L, storage.firstInstallTime)
        storage.firstInstallTime = 123_456_789L
        assertEquals(123_456_789L, storage.firstInstallTime)
    }

    @Test
    fun `resetForNewInstall clears the vid and mints a different one on next read`() {
        val original = storage.visitorId
        storage.resetForNewInstall()
        assertNotEquals(original, storage.visitorId)
    }

    @Test
    fun `resetForNewInstall clears installReported and referrer retry state`() {
        storage.installReported = true
        storage.referrerPending = true
        storage.referrerAttempts = 3

        storage.resetForNewInstall()

        assertFalse(storage.installReported)
        assertFalse(storage.referrerPending)
        assertEquals(0, storage.referrerAttempts)
    }

    @Test
    fun `resetForNewInstall clears the legacy broadcast referrer state too`() {
        // A broadcast referrer captured under the OLD (resurrected) install
        // must not leak into what the OS now says is a genuinely new one.
        storage.broadcastReferrer = "utm_source=vivo_store"
        storage.awaitingBroadcastReferrer = true

        storage.resetForNewInstall()

        assertTrue(storage.broadcastReferrer.isEmpty())
        assertFalse(storage.awaitingBroadcastReferrer)
    }

    @Test
    fun `resetForNewInstall clears session state so the next session starts fresh`() {
        storage.sessionId = "old-session"
        storage.sessionNumber = 7
        storage.sessionPvId = "pv-old"
        storage.sessionSequence = 4
        storage.sessionForegroundMs = 50_000L
        storage.sessionDay = "2020-1-1"
        storage.sessionLastActiveAt = 1L

        storage.resetForNewInstall()

        assertEquals("", storage.sessionId)
        assertEquals(0, storage.sessionNumber)
        assertEquals("", storage.sessionPvId)
        assertEquals(0, storage.sessionSequence)
        assertEquals(0L, storage.sessionForegroundMs)
        assertEquals("", storage.sessionDay)
        assertEquals(0L, storage.sessionLastActiveAt)
    }

    @Test
    fun `resetForNewInstall clears the beacon queue too`() {
        // A queued entry's JSON body has a vid baked in at enqueue time,
        // belonging to whatever install was current then. Delivering it
        // after a resurrection reset would attach a stale identity's beacon
        // to what is now a different install.
        storage.enqueue("""{"url":"https://example.com","path":"/x","body":"{}"}""")

        storage.resetForNewInstall()

        assertTrue(storage.queuedBeacons().isEmpty())
    }

    @Test
    fun `resetForNewInstall does NOT touch the clock offset`() {
        // Not tied to "which install this is" — a learned clock correction
        // is still valid for the same physical device regardless of vid.
        storage.clockOffsetSeconds = 42L

        storage.resetForNewInstall()

        assertEquals(42L, storage.clockOffsetSeconds)
    }

    @Test
    fun `firstInstallTime survives resetForNewInstall`() {
        // Roas sets firstInstallTime to the NEW value right after calling
        // resetForNewInstall — resetForNewInstall itself must not also wipe
        // it, or every subsequent launch would look resurrected again.
        storage.firstInstallTime = 999L
        storage.resetForNewInstall()
        assertEquals(999L, storage.firstInstallTime)
    }

    @Test
    fun `removeDelivered leaves an entry enqueued after the delivered set was read`() {
        // Reproduces the exact race Transport.flush() used to lose: read the
        // queue (as if about to deliver "a"), then something else enqueues "b"
        // before the removal actually happens. A blind overwrite based on the
        // stale read would wipe "b"; removeDelivered must not.
        storage.enqueue("a")
        val snapshot = storage.queuedBeacons() // ["a"] — as flush() would read before delivering
        storage.enqueue("b") // races in after the read, before removal
        storage.removeDelivered(snapshot) // removes "a" only, from the CURRENT queue

        assertEquals(listOf("b"), storage.queuedBeacons())
    }

    @Test
    fun `removeDelivered removes only one occurrence per delivered entry`() {
        storage.enqueue("dup")
        storage.enqueue("dup")
        storage.removeDelivered(listOf("dup"))
        assertEquals(listOf("dup"), storage.queuedBeacons())
    }

    @Test
    fun `visitorId is stable across repeated reads until reset`() {
        val first = storage.visitorId
        val second = storage.visitorId
        assertEquals(first, second)
        assertTrue(first.startsWith("rs"))
    }
}
