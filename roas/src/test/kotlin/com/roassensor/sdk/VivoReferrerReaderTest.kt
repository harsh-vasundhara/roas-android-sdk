package com.roassensor.sdk

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [VivoReferrerReader] against a fake `ContentProvider` registered at Vivo's
 * exact authority — more testable in this environment than
 * [InstallReferrerReader] itself, since Robolectric has a real
 * `ContentResolver` shadow (there is none for Play's binder service).
 *
 * The provider-absent case (no registration at all) is the one that matters
 * most: it's what every non-Vivo device in this session's real testing
 * actually hit, and it must degrade cleanly rather than throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VivoReferrerReaderTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    class FakeVivoProvider : ContentProvider() {
        companion object {
            var bundleToReturn: Bundle? = null
        }

        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            if (method == "read_referrer") bundleToReturn else null
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    @After
    fun tearDown() {
        FakeVivoProvider.bundleToReturn = null
    }

    /** Registers the fake with BOTH the ContentResolver (so `call` works) and
     *  the PackageManager (so `resolveContentProvider` finds it). The reader
     *  deliberately resolves first, to tell "no store on this device" from
     *  "store present, nothing for this install" — so a test that only did
     *  the former would model a device that cannot exist. */
    private fun registerProvider(authority: String = "com.vivo.appstore.provider.referrer") {
        Robolectric.buildContentProvider(FakeVivoProvider::class.java).create(authority)
        shadowOf(app.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                this.authority = authority
                packageName = "com.vivo.fake.store"
                name = FakeVivoProvider::class.java.name
            }
        )
    }

    @Test
    fun `finds the provider under the apprecommend authority too, not just appstore`() {
        // The regression this exists for: two real Indian-market handsets
        // (V2130, V2142) ship the Vivo store as `com.vivo.apprecommend`, so
        // its referrer provider answers at
        // `com.vivo.apprecommend.provider.referrer`. With only the
        // `com.vivo.appstore.*` authority hardcoded, both were silently
        // reported NOT_AVAILABLE while real referrer data was reachable.
        FakeVivoProvider.bundleToReturn = Bundle().apply {
            putString("install_referrer", "rsclid=fromApprecommend&rs_campaign=spring")
            putLong("referrer_click_timestamp_seconds", 1_000L)
            putLong("download_begin_timestamp_seconds", 1_005L)
        }
        registerProvider("com.vivo.apprecommend.provider.referrer")

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("OK", captured?.status)
        assertEquals("rsclid=fromApprecommend&rs_campaign=spring", captured?.referrer?.referrer)
    }

    @Test
    fun `a provider that answers with nothing is OK_EMPTY, never NOT_AVAILABLE`() {
        // The distinction that matters for diagnosis: "the store is here and
        // has no referrer for this install" is a different fact from "there
        // is no store on this device", and only the second should ever read
        // NOT_AVAILABLE. Exactly what the real V2130 returns for a
        // sideloaded app (`Result: Bundle[{}]`).
        FakeVivoProvider.bundleToReturn = Bundle()
        registerProvider("com.vivo.apprecommend.provider.referrer")

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("OK_EMPTY", captured?.status)
        assertNull(captured?.referrer)
    }

    @Test
    fun `reads a real referrer, click, and install timestamp from the provider bundle`() {
        FakeVivoProvider.bundleToReturn = Bundle().apply {
            putString("install_referrer", "rsclid=fromVivo&rs_campaign=spring")
            putLong("referrer_click_timestamp_seconds", 1_000L)
            putLong("download_begin_timestamp_seconds", 1_005L)
        }
        registerProvider()

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("OK", captured?.status)
        assertEquals("rsclid=fromVivo&rs_campaign=spring", captured?.referrer?.referrer)
        assertEquals(1_000L, captured?.referrer?.clickTimestampSeconds)
        assertEquals(1_005L, captured?.referrer?.installTimestampSeconds)
    }

    @Test
    fun `provider not registered at all reports NOT_AVAILABLE — the common case on any non-Vivo device`() {
        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("NOT_AVAILABLE", captured?.status)
        assertNull(captured?.referrer)
    }

    @Test
    fun `an empty referrer string reports OK_EMPTY, not a crash`() {
        FakeVivoProvider.bundleToReturn = Bundle().apply {
            putString("install_referrer", "")
        }
        registerProvider()

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("OK_EMPTY", captured?.status)
        assertNull(captured?.referrer)
    }

    @Test
    fun `a null bundle from a PRESENT provider is OK_EMPTY, not NOT_AVAILABLE`() {
        // Exactly what a real V2130 does: the store (com.vivo.apprecommend)
        // is installed and its provider resolves, but `call` hands back null
        // for a sideloaded app because the store holds referrer records only
        // for installs it performed. "Store here, nothing for you" must not
        // be reported as "no store on this device" — that mislabel is what
        // originally led to these handsets being written off as storeless.
        FakeVivoProvider.bundleToReturn = null
        registerProvider()

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("OK_EMPTY", captured?.status)
        assertNull(captured?.referrer)
    }
}
