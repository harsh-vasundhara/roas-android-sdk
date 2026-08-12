package com.roassensor.sdk

import java.util.Calendar
import java.util.UUID

/**
 * Sessions — one continuous visit, so the backend can replay a path rather than
 * hold a bag of unordered touches.
 *
 * The rules are mirrored **deliberately** from the web SDK's `session.ts`, and
 * they must not drift: a customer with a website and an app compares the two in
 * one dashboard, and a session that means "30 idle minutes" on web but something
 * else in the app makes that comparison quietly meaningless.
 *
 *  * 30 minutes of inactivity ends a session;
 *  * so does the visitor's **local** midnight — theirs, not UTC's, because
 *    "yesterday's session" should mean what the marketer's customer would say it
 *    means;
 *  * `sequence` is assigned HERE, on the device, because beacons race: the order
 *    they arrive in is not the order they happened in, so a server timestamp
 *    would reconstruct the wrong path.
 *
 * What is *not* from web: `number` (how many sessions this install has had) and
 * the foreground accumulator. A browser tab has no equivalent of "the app went
 * to the background", and retention cohorts — the reason app sessions are worth
 * collecting at all — need the count.
 *
 * State lives in [Storage] rather than memory so a session survives the process
 * being killed between two foregrounds, which on Android is routine rather than
 * exceptional.
 */
internal class SessionTracker(private val storage: Storage) {

    /** The live session. [started] is true only when THIS call created it. */
    data class Session(
        val id: String,
        val number: Int,
        val pvId: String,
        val started: Boolean,
    )

    /** When the current foreground stretch began (0 = we are backgrounded).
     *  In memory on purpose: a stretch cannot span a process death. */
    private var foregroundSince = 0L

    /**
     * The live session, rolling it over when it has gone stale. Callers should
     * send a session-start beacon only when [Session.started] comes back true —
     * otherwise every foreground would write a duplicate touch and dilute every
     * multi-touch credit split.
     */
    @Synchronized
    fun current(): Session {
        val now = System.currentTimeMillis()
        val id = storage.sessionId
        val alive = id.isNotEmpty() &&
            now - storage.sessionLastActiveAt < IDLE_MS &&
            storage.sessionDay == localDay()
        if (alive) {
            return Session(id, storage.sessionNumber, storage.sessionPvId, started = false)
        }

        val fresh = UUID.randomUUID().toString()
        storage.sessionId = fresh
        storage.sessionNumber = storage.sessionNumber + 1
        // One pv_id per session. The backend UPSERTS on it (ingest.record_touchpoint),
        // so the session's closing beacon folds its foreground time into the row
        // the opening beacon created instead of writing a second touch.
        storage.sessionPvId = "pv" + UUID.randomUUID().toString().replace("-", "")
        storage.sessionSequence = 0
        storage.sessionForegroundMs = 0L
        storage.sessionDay = localDay()
        storage.sessionLastActiveAt = now
        return Session(fresh, storage.sessionNumber, storage.sessionPvId, started = true)
    }

    /** This event's index within the session, and a touch of the idle clock. */
    @Synchronized
    fun nextSequence(): Int {
        current() // roll over first, so an event after 30 idle minutes starts a session
        val next = storage.sessionSequence + 1
        storage.sessionSequence = next
        storage.sessionLastActiveAt = System.currentTimeMillis()
        return next
    }

    /** The app came to the front. Call AFTER [current], so the rollover decision
     *  still sees how long the app was away. */
    @Synchronized
    fun markForeground() {
        foregroundSince = System.currentTimeMillis()
    }

    /**
     * The app went to the background. Returns the session's TOTAL foreground
     * milliseconds so far.
     *
     * Cumulative, not per-stretch, because a session routinely spans several
     * foreground stretches (a user checks a notification and comes back) and the
     * question the number answers is "how long were they actually in the app
     * this visit". The backend folds it in with a MAX, so re-reporting a total
     * is safe and a racing beacon can never revise it downward.
     */
    @Synchronized
    fun markBackground(): Long {
        val now = System.currentTimeMillis()
        if (foregroundSince > 0L) {
            storage.sessionForegroundMs = storage.sessionForegroundMs + (now - foregroundSince)
            foregroundSince = 0L
        }
        // The idle clock runs from when they LEFT, not from their last event —
        // otherwise an app left open in the background would keep one session
        // alive indefinitely.
        storage.sessionLastActiveAt = now
        return storage.sessionForegroundMs
    }

    private fun localDay(): String {
        val calendar = Calendar.getInstance() // default (device) timezone, as on web
        return "${calendar.get(Calendar.YEAR)}-" +
            "${calendar.get(Calendar.MONTH) + 1}-" +
            "${calendar.get(Calendar.DAY_OF_MONTH)}"
    }

    private companion object {
        /** Same 30 minutes as sdk/src/session.ts. */
        const val IDLE_MS = 30 * 60 * 1000L
    }
}
