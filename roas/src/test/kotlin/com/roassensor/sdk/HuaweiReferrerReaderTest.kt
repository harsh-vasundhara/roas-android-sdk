package com.roassensor.sdk

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
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
 * [HuaweiReferrerReader] against a fake `ContentProvider` registered at
 * Huawei's exact authority, returning a cursor shaped like AppGallery's real
 * one (referrer at column 0, click time at 1, install time at 2). See
 * [VivoReferrerReaderTest]'s doc comment for why this is more testable here
 * than Google's own reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HuaweiReferrerReaderTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    class FakeHuaweiProvider : ContentProvider() {
        companion object {
            var rowToReturn: List<String?>? = null // [referrer, clickTime, installTime]
        }

        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?,
            selectionArgs: Array<String>?, sortOrder: String?,
        ): Cursor? {
            val row = rowToReturn ?: return MatrixCursor(arrayOf("c0", "c1", "c2", "c3", "c4"))
            // Columns 0/1/2 are referrer/click/install; 3 unused; 4 is track id
            // (see HuaweiReferrerReader's own doc comment) — padded to 5 to
            // mirror the real provider's shape even though this reader never
            // reads column 3 or 4.
            val cursor = MatrixCursor(arrayOf("c0", "c1", "c2", "c3", "c4"))
            cursor.addRow(arrayOf(row.getOrNull(0), row.getOrNull(1), row.getOrNull(2), null, null))
            return cursor
        }
        override fun call(method: String, arg: String?, extras: android.os.Bundle?) = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    @After
    fun tearDown() {
        FakeHuaweiProvider.rowToReturn = null
    }

    private fun registerProvider() {
        Robolectric.buildContentProvider(FakeHuaweiProvider::class.java)
            .create("com.huawei.appmarket.commondata")
    }

    @Test
    fun `reads a real referrer, click, and install timestamp from the cursor row`() {
        FakeHuaweiProvider.rowToReturn = listOf("rsclid=fromHuawei&rs_campaign=spring", "1000", "1005")
        registerProvider()

        var captured: OemReferrer.Result? = null
        HuaweiReferrerReader.fetch(app) { captured = it }

        assertEquals("OK", captured?.status)
        assertEquals("rsclid=fromHuawei&rs_campaign=spring", captured?.referrer?.referrer)
        assertEquals(1000L, captured?.referrer?.clickTimestampSeconds)
        assertEquals(1005L, captured?.referrer?.installTimestampSeconds)
    }

    @Test
    fun `provider not registered at all reports NOT_AVAILABLE — the common case on any non-Huawei device`() {
        var captured: OemReferrer.Result? = null
        HuaweiReferrerReader.fetch(app) { captured = it }

        assertEquals("NOT_AVAILABLE", captured?.status)
        assertNull(captured?.referrer)
    }

    @Test
    fun `an empty referrer column reports OK_EMPTY, not a crash`() {
        FakeHuaweiProvider.rowToReturn = listOf("", "1000", "1005")
        registerProvider()

        var captured: OemReferrer.Result? = null
        HuaweiReferrerReader.fetch(app) { captured = it }

        assertEquals("OK_EMPTY", captured?.status)
        assertNull(captured?.referrer)
    }
}
