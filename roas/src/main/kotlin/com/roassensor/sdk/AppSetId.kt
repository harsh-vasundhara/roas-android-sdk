package com.roassensor.sdk

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * The Android **App Set ID** — a developer-scoped device identifier that
 * survives a GAID opt-out and resets only when every app from this developer is
 * uninstalled.
 *
 * ## What this is NOT for
 *
 * It is tempting to treat this as "the GAID replacement" and feed it to the
 * attribution identity graph, because it is stable exactly where the GAID has
 * gone blank. Google's policy forbids precisely that: the App Set ID may be used
 * for **analytics and fraud prevention**, and may **not** be used for ads
 * measurement or ads personalization. Attribution is ads measurement.
 *
 * So this value exists here for one job — spotting duplicate installs from a
 * single device — and the backend hashes it with a salt so it can never be
 * matched by an ad platform even by accident (`security.hash_app_set_id`).
 * It is deliberately not an `IdentityKey`.
 *
 * ## Availability
 *
 * Read reflectively, like [DeviceId], so the SDK never hard-depends on Play
 * Services. It additionally needs the host app to include
 * `com.google.android.gms:play-services-appset` — which is a separate artifact
 * from the ads-identifier one, and NOT transitively pulled in by it. An app
 * without it simply reports no App Set ID rather than failing.
 *
 * MUST be called off the main thread: the underlying task is awaited.
 */
internal object AppSetId {

    /** How long to wait for Play Services to answer. Short on purpose — this is
     *  a diagnostic/fraud field, and an install report must never be delayed for
     *  it. A timeout degrades to null exactly like an absent library. */
    private const val TIMEOUT_SECONDS = 2L

    /** The App Set ID, or null when the library is absent, Play Services can't
     *  answer, or the wait times out. */
    fun fetch(context: Context): String? {
        return try {
            val appSet = Class.forName("com.google.android.gms.appset.AppSet")
            val client = appSet
                .getMethod("getClient", Context::class.java)
                .invoke(null, context.applicationContext)
                ?: return null

            // Look the methods up on the declared INTERFACE, not on the concrete
            // instance's class: the implementation Play Services hands back is
            // package-private and obfuscated, so `client.javaClass.getMethod(…)`
            // resolves to a class we're not allowed to invoke on and throws
            // IllegalAccessException. (DeviceId can use javaClass because
            // AdvertisingIdClient.Info is a public static class — this one isn't.)
            val clientClass = Class.forName("com.google.android.gms.appset.AppSetIdClient")
            val task = clientClass.getMethod("getAppSetIdInfo").invoke(client) ?: return null

            val taskClass = Class.forName("com.google.android.gms.tasks.Task")
            val info = Class.forName("com.google.android.gms.tasks.Tasks")
                .getMethod(
                    "await",
                    taskClass,
                    Long::class.javaPrimitiveType,
                    TimeUnit::class.java,
                )
                .invoke(null, task, TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: return null

            val infoClass = Class.forName("com.google.android.gms.appset.AppSetIdInfo")
            (infoClass.getMethod("getId").invoke(info) as? String)?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            // ClassNotFoundException (library absent), ExecutionException,
            // TimeoutException, IllegalAccessException — every one of them means
            // "no App Set ID", and none of them may cost us the install report.
            null
        }
    }
}
