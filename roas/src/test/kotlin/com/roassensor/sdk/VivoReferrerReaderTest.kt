package com.roassensor.sdk

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
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

    private fun registerProvider() {
        Robolectric.buildContentProvider(FakeVivoProvider::class.java)
            .create("com.vivo.appstore.provider.referrer")
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
    fun `a null bundle from the provider also reports NOT_AVAILABLE`() {
        FakeVivoProvider.bundleToReturn = null
        registerProvider()

        var captured: OemReferrer.Result? = null
        VivoReferrerReader.fetch(app) { captured = it }

        assertEquals("NOT_AVAILABLE", captured?.status)
    }
}
