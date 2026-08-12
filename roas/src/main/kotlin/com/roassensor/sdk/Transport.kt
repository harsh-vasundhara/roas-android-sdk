package com.roassensor.sdk

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Delivers beacons: enqueue (persisted), then flush on a background thread.
 * Delivery is at-least-once and idempotent — the server dedups on `external_id`
 * / pv_id — so retrying a first-open after a flaky network is always safe.
 *
 * Every delivery attempt is logged (gated by [Roas.logLevel]) and reported to
 * [Roas.deliveryCallback] if one is set — this used to be entirely silent,
 * which made "did this beacon ever actually leave the device" undiagnosable
 * without attaching a debugger. Same motivation as the referrer_status fix:
 * a failure nobody can see is a failure nobody can fix.
 */
internal class Transport(
    baseUrl: String,
    private val storage: Storage,
    /** Signs beacons so the collector can tell this app from a `curl` carrying
     *  the public site key. Null until an app adopts signing — the server
     *  accepts unsigned beacons until the customer enforces. */
    private val appSecret: String? = null,
) {
    private val base = baseUrl.trimEnd('/')
    private val executor = Executors.newSingleThreadExecutor()

    /** Run work off the main thread (blocking reads like the GAID lookup). */
    fun background(block: () -> Unit) = executor.execute(block)

    /** Test-only: stop the delivery executor so a torn-down [Roas] singleton
     *  can't leak a background thread that races a later test's server. */
    internal fun shutdown() = executor.shutdownNow()

    fun send(path: String, body: JSONObject) {
        val entry = JSONObject()
            .put("url", base + path)
            .put("path", path)
            .put("body", body.toString())
            .toString()
        storage.enqueue(entry)
        flush()
    }

    fun flush() {
        executor.execute {
            val delivered = mutableListOf<String>()
            for (entry in storage.queuedBeacons()) {
                if (post(entry)) delivered.add(entry)
            }
            // Removes exactly the delivered entries from whatever the queue
            // holds AT REMOVAL TIME, rather than overwriting it with the
            // "remaining" list computed from the read above. Two sends fired
            // back-to-back from the same caller (Roas.sendSessionStart does
            // exactly this for its app_open + deferred-link pair) each queue
            // their own flush(); with a blind overwrite, the SECOND send's
            // enqueue() could land between this flush's read and its
            // writeback and be silently wiped by it — confirmed live: it was
            // losing the deferred-link beacon outright. removeDelivered
            // re-reads fresh, so a beacon enqueued mid-flush by another call
            // survives.
            storage.removeDelivered(delivered)
        }
    }

    private fun post(entry: String, isClockRetry: Boolean = false): Boolean {
        val obj = try {
            JSONObject(entry)
        } catch (e: Exception) {
            return true // malformed entry → drop, don't loop forever
        }
        val path = obj.optString("path", obj.optString("url"))
        return try {
            val url = URL(obj.getString("url"))
            val payload = obj.getString("body").toByteArray(Charsets.UTF_8)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            // Signed HERE, at transmit, not at enqueue. A queued beacon may sit
            // on the device for days waiting for a network — the whole reason
            // the queue is persisted — and a signature minted at enqueue time
            // would be long outside the server's freshness window by the time it
            // actually went out, so every offline install would be refused. The
            // event's own `ts` inside the body already carries when it happened;
            // this timestamp only proves the REQUEST is fresh.
            val now = System.currentTimeMillis() / 1000 + storage.clockOffsetSeconds
            Signer.header(appSecret, payload, now)?.let {
                conn.setRequestProperty(Signer.HEADER, it)
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val corrected = learnClockOffset(conn)
            conn.disconnect()

            // A 401 is normally permanent (bad secret) and would be dropped by
            // the `code < 500` rule below. But it is also what a badly-skewed
            // device clock produces, and dropping the install for that would be
            // silent data loss on exactly the handsets least able to report it.
            // So: if this response also taught us a new clock offset, re-sign
            // once with the corrected time. Once only — a genuinely wrong secret
            // must not become an infinite retry.
            if (code == 401 && corrected && !isClockRetry) {
                if (Roas.logLevel >= RoasLogLevel.DEBUG) {
                    Log.d(TAG, "$path -> 401; re-signing with corrected clock")
                }
                return post(entry, isClockRetry = true)
            }
            // 2xx delivered; 4xx = the server rejected it (bad/duplicate) so drop
            // it rather than retry forever. Only 5xx and network errors retry.
            val delivered = code < 500
            if (Roas.logLevel >= RoasLogLevel.DEBUG || (!delivered && Roas.logLevel >= RoasLogLevel.ERROR)) {
                Log.println(
                    if (delivered) Log.DEBUG else Log.WARN,
                    TAG,
                    "$path -> HTTP $code${if (delivered) "" else " (will retry)"}",
                )
            }
            Roas.deliveryCallback?.invoke(path, delivered, if (delivered) null else "HTTP $code")
            delivered
        } catch (e: Exception) {
            if (Roas.logLevel >= RoasLogLevel.ERROR) {
                Log.w(TAG, "$path -> ${e.javaClass.simpleName}: ${e.message} (will retry)")
            }
            Roas.deliveryCallback?.invoke(path, false, "${e.javaClass.simpleName}: ${e.message}")
            false // network error → keep and retry on the next flush / launch
        }
    }

    /**
     * Learn the device→server clock delta from the response's `Date` header.
     * Returns true when the correction MOVED, which is what makes a 401 worth
     * one retry.
     *
     * Only adopted past a threshold: a second or two of ordinary network and
     * processing latency is not clock skew, and rewriting the stored offset on
     * every beacon would make it jitter for no benefit.
     */
    private fun learnClockOffset(conn: HttpURLConnection): Boolean {
        val serverMillis = try {
            conn.getHeaderFieldDate("Date", 0L)
        } catch (e: Exception) {
            0L
        }
        if (serverMillis <= 0L) return false
        val offset = (serverMillis - System.currentTimeMillis()) / 1000
        val previous = storage.clockOffsetSeconds
        if (kotlin.math.abs(offset - previous) < CLOCK_SKEW_THRESHOLD_SECONDS) return false
        storage.clockOffsetSeconds = offset
        if (Roas.logLevel >= RoasLogLevel.DEBUG) {
            Log.d(TAG, "clock offset ${previous}s -> ${offset}s (from server Date)")
        }
        return true
    }

    private companion object {
        const val TAG = "RoasSensor"

        /** Below this, the difference is latency, not a wrong clock. */
        const val CLOCK_SKEW_THRESHOLD_SECONDS = 30L
    }
}
