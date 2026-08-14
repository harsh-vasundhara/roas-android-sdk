package com.roassensor.sdk.xiaomi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miui.referrer.annotation.GetAppsReferrerResponse
import com.miui.referrer.api.GetAppsReferrerClient
import com.miui.referrer.api.GetAppsReferrerStateListener
import com.roassensor.sdk.OemReferrerCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real Xiaomi GetApps referrer read, kept in this opt-in module (see
 * `com.roassensor.sdk.XiaomiReferrerBridge`'s doc comment in `:roas` core for
 * why) so an app that doesn't target the Xiaomi/MIUI/HyperOS market never
 * pays for `com.miui.referrer:homereferrer`.
 *
 * Structurally mirrors `com.roassensor.sdk.InstallReferrerReader` — Xiaomi's
 * `GetAppsReferrerClient` is deliberately shaped like Google's own
 * `InstallReferrerClient` (same async connect/listener/details pattern) —
 * including the same 5s timeout guard: the exact failure mode that guard
 * exists for (a service connection that never answers, confirmed live on a
 * Vivo running Funtouch OS) is not Google-specific.
 *
 * `@JvmStatic` is what makes [fetch] a genuine static method
 * `Method.invoke(null, ...)` can call from `:roas` core's reflective caller
 * without an instance — a plain Kotlin `object` only exposes instance
 * methods on a singleton `INSTANCE` field otherwise.
 */
object XiaomiReferrerBridgeImpl {

    private const val TAG = "RoasXiaomiReferrer"
    private const val TIMEOUT_MS = 5_000L

    @JvmStatic
    fun fetch(context: Context, callback: OemReferrerCallback) {
        val client = GetAppsReferrerClient.Builder(context.applicationContext).build()
        val answered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (answered.compareAndSet(false, true)) {
                try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                Log.d(TAG, "Xiaomi referrer read timed out after ${TIMEOUT_MS}ms")
                callback.onResult(null, null, null, "TIMEOUT")
            }
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        try {
            client.startConnection(object : GetAppsReferrerStateListener {
                override fun onGetAppsReferrerSetupFinished(state: Int) {
                    if (!answered.compareAndSet(false, true)) return // the timeout already answered
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        if (state == GetAppsReferrerResponse.OK) {
                            val details = client.installReferrer
                            val raw = details.installReferrer ?: ""
                            if (raw.isEmpty()) {
                                callback.onResult(null, null, null, "OK_EMPTY")
                            } else {
                                val click = details.referrerClickTimestampSeconds.takeIf { it > 0 }
                                val install = details.installBeginTimestampSeconds.takeIf { it > 0 }
                                callback.onResult(raw, click, install, "OK")
                            }
                        } else {
                            callback.onResult(null, null, null, responseCodeName(state))
                        }
                    } catch (e: Exception) {
                        callback.onResult(null, null, null, "EXCEPTION:${e.javaClass.simpleName}")
                    } finally {
                        try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                    }
                }

                override fun onGetAppsServiceDisconnected() {
                    // Same reasoning as InstallReferrerReader's own empty
                    // override: the shared timeout above is what actually
                    // saves a device where this fires (or nothing fires at
                    // all) on every single launch.
                }
            })
        } catch (t: Throwable) {
            // GetApps isn't present at all — the common case on any
            // non-Xiaomi device that still somehow ended up with this module
            // on the classpath (a multi-brand build, say).
            if (answered.compareAndSet(false, true)) {
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "Xiaomi referrer unavailable: ${t.javaClass.simpleName}")
                callback.onResult(null, null, null, "NOT_AVAILABLE")
            }
        }
    }

    private fun responseCodeName(code: Int): String = when (code) {
        GetAppsReferrerResponse.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        GetAppsReferrerResponse.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        else -> "UNKNOWN_$code"
    }
}
