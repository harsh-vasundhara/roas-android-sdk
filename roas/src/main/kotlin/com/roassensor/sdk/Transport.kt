package com.roassensor.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Delivers beacons: enqueue (persisted), then flush on a background thread.
 * Delivery is at-least-once and idempotent — the server dedups on `external_id`
 * / pv_id — so retrying a first-open after a flaky network is always safe.
 */
internal class Transport(baseUrl: String, private val storage: Storage) {
    private val base = baseUrl.trimEnd('/')
    private val executor = Executors.newSingleThreadExecutor()

    /** Run work off the main thread (blocking reads like the GAID lookup). */
    fun background(block: () -> Unit) = executor.execute(block)

    fun send(path: String, body: JSONObject) {
        val entry = JSONObject()
            .put("url", base + path)
            .put("body", body.toString())
            .toString()
        storage.enqueue(entry)
        flush()
    }

    fun flush() {
        executor.execute {
            val remaining = mutableListOf<String>()
            for (entry in storage.queuedBeacons()) {
                if (!post(entry)) remaining.add(entry) // couldn't deliver → keep
            }
            storage.replaceQueue(remaining)
        }
    }

    private fun post(entry: String): Boolean {
        return try {
            val obj = JSONObject(entry)
            val url = URL(obj.getString("url"))
            val payload = obj.getString("body").toByteArray(Charsets.UTF_8)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            conn.disconnect()
            // 2xx delivered; 4xx = the server rejected it (bad/duplicate) so drop
            // it rather than retry forever. Only 5xx and network errors retry.
            code < 500
        } catch (e: Exception) {
            false // network error → keep and retry on the next flush / launch
        }
    }
}
