package com.roassensor.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.UUID

/**
 * On-device state: the stable visitor id, the "install already reported" flag,
 * and a persisted beacon queue. The queue survives process death on purpose —
 * an install that happens with no network must still be reported on the next
 * launch, never lost.
 */
internal class Storage(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stable first-party visitor id for this install (our "vid"). Minted
     *  once and reused; matches the server's vid regex `[A-Za-z0-9_-]{8,64}`. */
    val visitorId: String
        @Synchronized get() {
            prefs.getString(KEY_VID, null)?.let { return it }
            val vid = "rs" + UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_VID, vid).apply()
            return vid
        }

    var installReported: Boolean
        get() = prefs.getBoolean(KEY_INSTALL_REPORTED, false)
        @Synchronized set(value) {
            prefs.edit().putBoolean(KEY_INSTALL_REPORTED, value).apply()
        }

    @Synchronized
    fun enqueue(entry: String) {
        val list = queuedBeacons().toMutableList()
        list.add(entry)
        // Bound the queue so a permanently-offline device can't grow it without
        // limit; oldest drop first.
        while (list.size > MAX_QUEUE) list.removeAt(0)
        replaceQueue(list)
    }

    @Synchronized
    fun queuedBeacons(): List<String> {
        val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Synchronized
    fun replaceQueue(entries: List<String>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e)
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private companion object {
        const val PREFS = "com.roassensor.sdk"
        const val KEY_VID = "vid"
        const val KEY_INSTALL_REPORTED = "install_reported"
        const val KEY_QUEUE = "queue"
        const val MAX_QUEUE = 500
    }
}
