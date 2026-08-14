package com.roassensor.sdk.samsung

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roassensor.sdk.OemReferrerCallback
import com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerClient
import com.samsung.android.sdk.sinstallreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real Samsung Galaxy Store referrer read, kept in this opt-in module
 * (see `com.roassensor.sdk.SamsungReferrerBridge`'s doc comment in `:roas`
 * core for why) so an app that doesn't target the Samsung market never pays
 * for `samsung_galaxystore_install_referrer`.
 *
 * Samsung's `InstallReferrerClient` is structurally near-identical to
 * Google's own (`com.roassensor.sdk.InstallReferrerReader`) — same
 * class/method/listener names, different package — right down to including
 * the same 5s timeout guard for a service connection that never answers.
 *
 * `@JvmStatic` is what makes [fetch] a genuine static method
 * `Method.invoke(null, ...)` can call from `:roas` core's reflective caller
 * without an instance.
 */
object SamsungReferrerBridgeImpl {

    private const val TAG = "RoasSamsungReferrer"
    private const val TIMEOUT_MS = 5_000L

    @JvmStatic
    fun fetch(context: Context, callback: OemReferrerCallback) {
        val client = InstallReferrerClient.newBuilder(context.applicationContext).build()
        val answered = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (answered.compareAndSet(false, true)) {
                try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                Log.d(TAG, "Samsung referrer read timed out after ${TIMEOUT_MS}ms")
                callback.onResult(null, null, null, "TIMEOUT")
            }
        }
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (!answered.compareAndSet(false, true)) return // the timeout already answered
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
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
                            callback.onResult(null, null, null, responseCodeName(responseCode))
                        }
                    } catch (e: Exception) {
                        callback.onResult(null, null, null, "EXCEPTION:${e.javaClass.simpleName}")
                    } finally {
                        try { client.endConnection() } catch (e: Exception) { /* ignore */ }
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Same reasoning as InstallReferrerReader's own empty
                    // override: the shared timeout above is what actually
                    // saves a device where this fires (or nothing fires at
                    // all) on every single launch.
                }
            })
        } catch (t: Throwable) {
            // Galaxy Store isn't present at all — the common case on any
            // non-Samsung device that still somehow ended up with this
            // module on the classpath (a multi-brand build, say).
            if (answered.compareAndSet(false, true)) {
                handler.removeCallbacks(timeoutRunnable)
                Log.d(TAG, "Samsung referrer unavailable: ${t.javaClass.simpleName}")
                callback.onResult(null, null, null, "NOT_AVAILABLE")
            }
        }
    }

    private fun responseCodeName(code: Int): String = when (code) {
        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        else -> "UNKNOWN_$code"
    }
}
