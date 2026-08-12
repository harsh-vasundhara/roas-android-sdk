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

    /** True when the install was reported but the Play referrer read failed for
     *  a *transient* reason, so a later launch should try again. The install
     *  itself is never re-reported — only the recovered referrer is, as an
     *  app_open. See [Roas.retryReferrer]. */
    var referrerPending: Boolean
        get() = prefs.getBoolean(KEY_REFERRER_PENDING, false)
        @Synchronized set(value) {
            prefs.edit().putBoolean(KEY_REFERRER_PENDING, value).apply()
        }

    /** How many launches have already retried the referrer. Bounded so a device
     *  whose Play Services never answers doesn't pay for the attempt forever. */
    var referrerAttempts: Int
        get() = prefs.getInt(KEY_REFERRER_ATTEMPTS, 0)
        @Synchronized set(value) {
            prefs.edit().putInt(KEY_REFERRER_ATTEMPTS, value).apply()
        }

    /**
     * Seconds to ADD to this device's clock to get server time, learned from the
     * HTTP `Date` response header.
     *
     * Persisted rather than kept in memory because the very first beacon of a
     * cold launch is the install — the one beacon that matters most — and a
     * handset whose clock is hours out (dead battery, wrong timezone, no NTP)
     * would sign it with a timestamp outside the server's tolerance and have it
     * refused. Remembering the correction means only the first beacon a device
     * ever sends is exposed to that.
     */
    var clockOffsetSeconds: Long
        get() = prefs.getLong(KEY_CLOCK_OFFSET, 0L)
        @Synchronized set(value) {
            prefs.edit().putLong(KEY_CLOCK_OFFSET, value).apply()
        }

    // ── Session state (see SessionTracker) ───────────────────────────────────
    // Persisted rather than held in memory because Android kills backgrounded
    // processes routinely: a session that lived only in RAM would restart every
    // time the OS reclaimed the app, inflating session counts and destroying the
    // retention numbers sessions exist to produce.

    var sessionId: String
        get() = prefs.getString(KEY_SESSION_ID, "") ?: ""
        @Synchronized set(value) { prefs.edit().putString(KEY_SESSION_ID, value).apply() }

    var sessionNumber: Int
        get() = prefs.getInt(KEY_SESSION_NUMBER, 0)
        @Synchronized set(value) { prefs.edit().putInt(KEY_SESSION_NUMBER, value).apply() }

    var sessionPvId: String
        get() = prefs.getString(KEY_SESSION_PV_ID, "") ?: ""
        @Synchronized set(value) { prefs.edit().putString(KEY_SESSION_PV_ID, value).apply() }

    var sessionSequence: Int
        get() = prefs.getInt(KEY_SESSION_SEQUENCE, 0)
        @Synchronized set(value) { prefs.edit().putInt(KEY_SESSION_SEQUENCE, value).apply() }

    var sessionForegroundMs: Long
        get() = prefs.getLong(KEY_SESSION_FOREGROUND_MS, 0L)
        @Synchronized set(value) { prefs.edit().putLong(KEY_SESSION_FOREGROUND_MS, value).apply() }

    /** The visitor's local calendar day, for the midnight rollover. */
    var sessionDay: String
        get() = prefs.getString(KEY_SESSION_DAY, "") ?: ""
        @Synchronized set(value) { prefs.edit().putString(KEY_SESSION_DAY, value).apply() }

    var sessionLastActiveAt: Long
        get() = prefs.getLong(KEY_SESSION_LAST_ACTIVE, 0L)
        @Synchronized set(value) { prefs.edit().putLong(KEY_SESSION_LAST_ACTIVE, value).apply() }

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
        const val KEY_REFERRER_PENDING = "referrer_pending"
        const val KEY_REFERRER_ATTEMPTS = "referrer_attempts"
        const val KEY_QUEUE = "queue"
        const val KEY_CLOCK_OFFSET = "clock_offset"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SESSION_NUMBER = "session_number"
        const val KEY_SESSION_PV_ID = "session_pv_id"
        const val KEY_SESSION_SEQUENCE = "session_sequence"
        const val KEY_SESSION_FOREGROUND_MS = "session_foreground_ms"
        const val KEY_SESSION_DAY = "session_day"
        const val KEY_SESSION_LAST_ACTIVE = "session_last_active"
        const val MAX_QUEUE = 500
    }
}
