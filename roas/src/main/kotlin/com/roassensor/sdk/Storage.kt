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

    /** Whatever [InstallReferrerBroadcastReceiver] last caught from the legacy
     *  `INSTALL_REFERRER` broadcast, decoded and waiting to be sent — cleared
     *  the moment [Roas] actually uses it (see [ReferrerFallback]), so a stale
     *  value from a previous install can never leak into a later one. */
    var broadcastReferrer: String
        get() = prefs.getString(KEY_BROADCAST_REFERRER, "") ?: ""
        @Synchronized set(value) {
            prefs.edit().putString(KEY_BROADCAST_REFERRER, value).apply()
        }

    /** True when the install beacon went out with no referrer at all (Play
     *  gave nothing AND the broadcast hadn't arrived yet) and it's still
     *  worth checking once more on the next launch — the broadcast can race
     *  the very first open by a few seconds even on a device where Play will
     *  never have an answer (a non-Play-Store install). Checked exactly once
     *  more, then given up regardless of outcome: unlike [referrerAttempts]
     *  this costs nothing to check (a local read, no service binding), but an
     *  install that genuinely has no referrer from any source must not be
     *  checked forever. */
    var awaitingBroadcastReferrer: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_BROADCAST_REFERRER, false)
        @Synchronized set(value) {
            prefs.edit().putBoolean(KEY_AWAITING_BROADCAST_REFERRER, value).apply()
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

    /**
     * The `PackageManager.firstInstallTime` this SDK last saw for THIS
     * package, so [Roas] can tell a genuinely fresh OS-level install apart
     * from private app data (this very SharedPreferences file) that survived
     * one. 0L means "never recorded" — treated as a fresh install too.
     *
     * Confirmed necessary by a live Vivo device test: uninstalling and
     * reinstalling the app through the on-device UI produced the SAME `vid`
     * and `installReported=true` every time — some OEM data-retention layer
     * (or the Android 10+ "keep app data" uninstall option, or Auto Backup)
     * preserved this SDK's own SharedPreferences file across what was, at the
     * OS level, a genuine new package install. `firstInstallTime` is immune
     * to that class of restore because it is not part of the app's private
     * data directory at all — it lives in the package manager's own registry
     * (`/data/system/packages.xml`), assigned fresh by the OS at actual
     * install time, so nothing that clones/restores `/data/data/<pkg>` can
     * fake it.
     */
    var firstInstallTime: Long
        get() = prefs.getLong(KEY_FIRST_INSTALL_TIME, 0L)
        @Synchronized set(value) {
            prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, value).apply()
        }

    /**
     * Wipe every field tied to "this specific install" — vid, the
     * install/referrer flags, all session state, AND the pending beacon
     * queue — so a device whose private data survived a real OS-level
     * reinstall starts exactly as clean as one where SharedPreferences was
     * genuinely cleared. Called from [Roas] only when [firstInstallTime]
     * proves the OS actually re-installed the package.
     *
     * The queue is NOT the same kind of "not tied to which install this is"
     * as [clockOffsetSeconds] below, and clearing it here was added after
     * live testing on a device whose data survived several rapid
     * uninstall/reinstall cycles: every queued entry's JSON body has a `vid`
     * baked in at `baseBody()`/enqueue time — belonging to whatever install
     * was current when it was queued. If that install turns out to have been
     * resurrected data and gets reset here, a beacon still sitting in the
     * queue was minted under the OLD identity, and delivering it AFTER the
     * reset would attach a stale vid's beacon to what is now a different
     * install — exactly the kind of cross-install mix-up this reset exists
     * to prevent, just moved into the queue instead of prevented by it.
     * Losing an unsent beacon from an identity we've just decided this
     * device is no longer using is the correct trade — it was never going to
     * resolve to anything real on the backend anyway.
     */
    @Synchronized
    fun resetForNewInstall() {
        prefs.edit()
            .remove(KEY_VID)
            .remove(KEY_INSTALL_REPORTED)
            .remove(KEY_REFERRER_PENDING)
            .remove(KEY_REFERRER_ATTEMPTS)
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_NUMBER)
            .remove(KEY_SESSION_PV_ID)
            .remove(KEY_SESSION_SEQUENCE)
            .remove(KEY_SESSION_FOREGROUND_MS)
            .remove(KEY_SESSION_DAY)
            .remove(KEY_SESSION_LAST_ACTIVE)
            .remove(KEY_QUEUE)
            .remove(KEY_BROADCAST_REFERRER)
            .remove(KEY_AWAITING_BROADCAST_REFERRER)
            .apply()
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

    /**
     * Remove exactly [delivered] from whatever the queue holds RIGHT NOW —
     * read and write in the same synchronized call, not [queuedBeacons] then
     * [replaceQueue] as two separate ones.
     *
     * [Transport.flush] used to read the queue, spend real time on network
     * I/O delivering each entry, then call [replaceQueue] with the
     * "remaining" list it computed from that now-stale read. Two `send()`
     * calls fired back-to-back — which `Roas.sendSessionStart`'s app_open +
     * deferred-link pair does exactly — race exactly that gap: the second
     * `enqueue()` can land between the first flush's stale read and its
     * write-back and be silently wiped by it. Confirmed live in a unit test:
     * the deferred-link beacon was vanishing outright, with no error, no
     * delivery callback, nothing — `flush()`'s own overwrite was the one
     * throwing it away. Reading fresh at removal time closes that window.
     */
    @Synchronized
    fun removeDelivered(delivered: List<String>) {
        if (delivered.isEmpty()) return
        val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
        val remaining = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        for (entry in delivered) remaining.remove(entry) // removes one occurrence per delivered entry
        val out = JSONArray()
        for (e in remaining) out.put(e)
        prefs.edit().putString(KEY_QUEUE, out.toString()).apply()
    }

    private companion object {
        const val PREFS = "com.roassensor.sdk"
        const val KEY_VID = "vid"
        const val KEY_INSTALL_REPORTED = "install_reported"
        const val KEY_REFERRER_PENDING = "referrer_pending"
        const val KEY_REFERRER_ATTEMPTS = "referrer_attempts"
        const val KEY_BROADCAST_REFERRER = "broadcast_referrer"
        const val KEY_AWAITING_BROADCAST_REFERRER = "awaiting_broadcast_referrer"
        const val KEY_QUEUE = "queue"
        const val KEY_CLOCK_OFFSET = "clock_offset"
        const val KEY_FIRST_INSTALL_TIME = "first_install_time"
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
