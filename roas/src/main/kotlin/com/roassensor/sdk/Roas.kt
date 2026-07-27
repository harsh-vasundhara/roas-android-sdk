package com.roassensor.sdk

import android.content.Context
import org.json.JSONObject

/**
 * ROASSensor Android SDK — the public entry point.
 *
 * ```kotlin
 * // Application.onCreate()
 * Roas.initialize(this, publicKey = "YOUR-SITE-PUBLIC-KEY")
 *
 * // when the user is known
 * Roas.identify(email = "buyer@example.com")
 *
 * // pass the visitor id to RevenueCat so purchases attribute to this install
 * Purchases.configure(PurchasesConfiguration.Builder(this, rcApiKey)
 *     .appUserID(Roas.visitorId()).build())
 * ```
 *
 * On first launch it reports the install — reading the Play install referrer and
 * (with consent) the GAID — which links this install to the ad click that drove
 * it. Everything is delivered through a persisted queue, so an install that
 * happens offline is reported on the next launch, never lost.
 */
object Roas {
    private const val DEFAULT_BASE_URL = "https://api.roassensor.com"

    private lateinit var appContext: Context
    private lateinit var publicKey: String
    private lateinit var storage: Storage
    private lateinit var transport: Transport
    @Volatile private var initialized = false

    /**
     * Start the SDK. Call once, as early as possible (Application.onCreate).
     * Idempotent — only the first call initializes.
     *
     * @param publicKey  the app property's public key from ROASSensor setup
     * @param customerUserId  your own user id if already known; bound as an
     *        external id so the same person on web, another device, or this app
     *        collapses into one identity
     * @param baseUrl  override the collector host (defaults to production)
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        publicKey: String,
        customerUserId: String? = null,
        baseUrl: String = DEFAULT_BASE_URL,
    ) {
        if (initialized) return
        appContext = context.applicationContext
        this.publicKey = publicKey
        storage = Storage(appContext)
        transport = Transport(baseUrl, storage)
        initialized = true

        transport.flush() // deliver anything queued from a previous offline launch
        if (!storage.installReported) {
            reportFirstOpen(customerUserId)
        } else if (customerUserId != null) {
            identify(customerUserId = customerUserId)
        }
    }

    /**
     * The stable visitor id for this install. Pass it to RevenueCat as the
     * `appUserID` so a purchase carries it back to us and attributes to the
     * install (and its ad click). Null before [initialize].
     */
    @JvmStatic
    fun visitorId(): String? = if (initialized) storage.visitorId else null

    /** Bind the user's identity. At least one of the arguments must be non-null. */
    @JvmStatic
    @JvmOverloads
    fun identify(email: String? = null, phone: String? = null, customerUserId: String? = null) {
        if (!initialized) return
        val body = baseBody()
        Hashing.hashEmail(email).takeIf { it.isNotEmpty() }?.let { body.put("email_hash", it) }
        Hashing.hashPhone(phone).takeIf { it.isNotEmpty() }?.let { body.put("phone_hash", it) }
        customerUserId?.let { body.put("external_id", it) }
        if (body.has("email_hash") || body.has("phone_hash") || body.has("external_id")) {
            transport.send("/api/tracking/mobile/identify", body)
        }
    }

    /** Record a funnel/behaviour event (never revenue — see [RoasEvent]). */
    @JvmStatic
    @JvmOverloads
    fun track(event: RoasEvent, name: String? = null, properties: Map<String, Any>? = null) {
        if (!initialized) return
        val body = baseBody().put("name", event.key)
        name?.let { body.put("label", it) }
        properties?.let { body.put("props", JSONObject(it)) }
        transport.send("/api/tracking/mobile/events", body)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun reportFirstOpen(customerUserId: String?) {
        // Read the GAID (blocking) and the install referrer (async) off the main
        // thread, then post one first-open carrying both.
        transport.background {
            val gaid = DeviceId.advertisingId(appContext)
            InstallReferrerReader.fetch(appContext) { referrer ->
                val body = baseBody()
                    .put("os", "Android")
                    .put("device_type", "mobile")
                    .put("app_version", appVersion())
                gaid?.let { body.put("device_id", it) } // raw; the server hashes it
                referrer?.let {
                    body.put("install_referrer", it.referrer)
                    it.clickToInstallSeconds?.let { s -> body.put("click_to_install_seconds", s) }
                }
                customerUserId?.let { body.put("external_id", it) }
                transport.send("/api/tracking/mobile/first-open", body)
                storage.installReported = true
            }
        }
    }

    private fun baseBody(): JSONObject =
        JSONObject().put("site", publicKey).put("vid", storage.visitorId)

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
