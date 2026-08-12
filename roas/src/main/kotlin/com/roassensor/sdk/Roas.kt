package com.roassensor.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
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

    /** Launches to keep retrying a transiently-failed referrer read before
     *  giving up. Play attributes an install for far longer than five launches,
     *  so the bound costs no real recovery — it just stops a device whose Play
     *  Services never answers from paying for the attempt forever. */
    private const val MAX_REFERRER_ATTEMPTS = 5

    private lateinit var appContext: Context
    private lateinit var publicKey: String
    private lateinit var storage: Storage
    private lateinit var transport: Transport
    private lateinit var sessions: SessionTracker
    @Volatile private var initialized = false

    /** How much [Transport] writes to Logcat. Read by [Transport] on every
     *  delivery attempt — see [setLogLevel]. */
    @Volatile internal var logLevel: RoasLogLevel = RoasLogLevel.ERROR
        private set

    /** Fired by [Transport] after every delivery attempt, success or failure.
     *  Optional — most apps don't need it — but without it there was no way
     *  for the host app to know a beacon ever failed short of watching
     *  Logcat by hand. `error` is null on success. */
    @Volatile internal var deliveryCallback: ((path: String, success: Boolean, error: String?) -> Unit)? = null

    /** Controls Logcat verbosity. Defaults to [RoasLogLevel.ERROR] — quiet in
     *  a release build, but loud enough that a device that can never deliver
     *  anything shows up immediately instead of needing a network debugger. */
    @JvmStatic
    fun setLogLevel(level: RoasLogLevel) {
        logLevel = level
    }

    /** Observe delivery results (e.g. to surface a debug banner in a QA
     *  build, or forward to your own crash/analytics tooling). Pass null to
     *  stop observing. */
    @JvmStatic
    fun setOnDeliveryResult(callback: ((path: String, success: Boolean, error: String?) -> Unit)?) {
        deliveryCallback = callback
    }

    /**
     * Start the SDK. Call once, as early as possible (Application.onCreate).
     * Idempotent — only the first call initializes.
     *
     * @param publicKey  the app property's public key from ROASSensor setup
     * @param customerUserId  your own user id if already known; bound as an
     *        external id so the same person on web, another device, or this app
     *        collapses into one identity
     * @param appSecret  this property's beacon signing secret, from Setup →
     *        your app → Beacon signing. Optional: without it beacons go
     *        unsigned, which the collector accepts until the customer turns on
     *        "require signed beacons". Do NOT paste it as a string literal —
     *        put it in `local.properties` and expose it via `buildConfigField`
     *        so it stays out of source control. It is not a revenue credential
     *        (that is the server-side api key, which must never ship in an app);
     *        the worst an extracted one allows is forging beacons, which the
     *        public key alone already allowed — and rotating it invalidates
     *        every build carrying the old one.
     * @param baseUrl  override the collector host (defaults to production)
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        publicKey: String,
        customerUserId: String? = null,
        baseUrl: String = DEFAULT_BASE_URL,
        // LAST on purpose, and it must stay last. This SDK is published on Maven
        // Central, so callers exist that we cannot see or recompile — including
        // the Flutter bridge, which passes these POSITIONALLY. `appSecret` was
        // briefly added in the middle of this list, which silently rebound every
        // such call one slot along: customerUserId arrived as the signing
        // secret, baseUrl as the customer id, and baseUrl fell back to the
        // production default. It still compiled — every type lined up — so a
        // Flutter app would have signed with garbage (401 on every beacon) while
        // quietly posting to the wrong host. New parameters go on the END.
        appSecret: String? = null,
    ) {
        if (initialized) return
        appContext = context.applicationContext
        this.publicKey = publicKey
        storage = Storage(appContext)
        transport = Transport(baseUrl, storage, appSecret)
        sessions = SessionTracker(storage)
        initialized = true

        // Resolve the session BEFORE anything reports and before the lifecycle
        // callbacks are registered. Otherwise the first Activity.onStart races
        // reportFirstOpen: whichever wins creates session 1 and the loser emits a
        // SECOND touch for the same moment — an install and an app_open a
        // millisecond apart. That is exactly the phantom-touch duplication the
        // pv_id upsert exists to prevent, and it would dilute every multi-touch
        // credit split for that visitor.
        val session = sessions.current()
        sessions.markForeground()
        (appContext as? Application)?.let { registerLifecycle(it) }

        transport.flush() // deliver anything queued from a previous offline launch
        if (!storage.installReported) {
            // The install touchpoint IS this session's opening beacon — it carries
            // the session's pv_id, so the closing beacon folds foreground time
            // into that row rather than writing an app_open beside it.
            reportFirstOpen(customerUserId)
        } else {
            // A returning user whose session had expired. If it had NOT — the app
            // was killed and relaunched inside 30 minutes — `started` is false and
            // nothing is sent, because that is still the same visit.
            if (session.started) sendSessionStart(session)
            // The install is already on record, but its referrer may not be:
            // a transient Play failure on the very first launch used to be
            // permanent, because installReported was set regardless of whether
            // the read succeeded. Give it another try here.
            if (storage.referrerPending) retryReferrer()
            if (customerUserId != null) identify(customerUserId = customerUserId)
        }
    }

    /**
     * Watch the app cross the foreground/background line, which is the only
     * signal a session boundary can be derived from.
     *
     * Counted across activities rather than taken from any single one: an app
     * moving from activity A to B stops A and starts B, and treating either as a
     * background/foreground would shred one visit into several.
     *
     * `isChangingConfigurations` is the exception that has to be handled
     * explicitly — a device rotation genuinely destroys and recreates the
     * activity, so the count really does reach zero, and without this check every
     * rotation would close and reopen a session.
     */
    private fun registerLifecycle(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var recreating = false

            // Both handlers CLAMP the counter at zero instead of trusting its
            // absolute value, because we cannot assume we were registered before
            // the first activity started.
            //
            // A Kotlin app calls initialize() from Application.onCreate(), which
            // is genuinely before any activity exists — so the count began at 0
            // correctly. A **Flutter** app cannot: initialize() arrives from Dart
            // main(), which the engine runs inside FlutterActivity.onStart(),
            // *after* super.onStart() has already dispatched onActivityStarted to
            // the callbacks registered at that moment. Ours is not among them.
            //
            // Unclamped, that one missed start poisoned the counter forever: it
            // went 0 → -1 on the first background, and every later
            // `wasBackground = started == 0` then read false, so onEnterForeground
            // NEVER fired again. Measured on a V2130 — engagement_ms froze at
            // 27824 across two foreground stretches that should have totalled
            // ~48000, with the pid unchanged so the process had not been killed.
            // Worse than the lost engagement: sendSessionStart is reachable only
            // from onEnterForeground and initialize, so no returning user ever
            // produced a session-start beacon, session_number stopped being
            // reported past the first session, and retention.py's sessionCoverage
            // could never reach 100% — which withholds the retention headline for
            // the whole site.
            //
            // Clamping is a no-op for the Kotlin path (the count never goes
            // negative there) and self-heals the Flutter one on the first
            // background/foreground pair.
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = started <= 0
                started = maxOf(0, started) + 1
                if (wasBackground && !recreating) onEnterForeground()
                recreating = false
            }

            override fun onActivityStopped(activity: Activity) {
                started = maxOf(0, started - 1)
                if (started > 0) return
                if (activity.isChangingConfigurations) recreating = true else onEnterBackground()
            }

            override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun onEnterForeground() {
        val session = sessions.current()
        sessions.markForeground()
        if (session.started) sendSessionStart(session)
    }

    private fun onEnterBackground() {
        val foregroundMs = sessions.markBackground()
        // Upserts onto the session's opening touch via its pv_id, so a visit is
        // one row that gains its duration — not two rows that split its credit.
        // Sent on every backgrounding rather than only at session expiry, because
        // there is no reliable "app is being killed" callback on Android: waiting
        // for one would lose the engagement of every session the OS reclaims.
        val body = baseBody()
            .put("os", "Android")
            .put("event_type", "app_open")
            .put("pv_id", storage.sessionPvId)
            .put("engagement_ms", foregroundMs)
        transport.send("/api/tracking/mobile/first-open", body)
    }

    /** The opening beacon for a session that is not an install. */
    private fun sendSessionStart(session: SessionTracker.Session) {
        val body = baseBody()
            .put("os", "Android")
            .put("event_type", "app_open")
            .put("app_version", appVersion())
            .put("pv_id", session.pvId)
            .put("session_number", session.number)
        DeviceInfo.describe(appContext, body)
        transport.send("/api/tracking/mobile/first-open", body)
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

    /**
     * Forward a direct deep link (an Android App Link that opened this app
     * while it was already installed) so its `rsclid` attributes this open
     * deterministically — the Android twin of `Roas.swift`'s
     * `handleDeepLink`. This is a *different* case from the Play Install
     * Referrer (that's the *install*; this is a later open of an app that
     * was already on the device). A no-op if the URL carries no `rsclid`.
     */
    @JvmStatic
    fun handleDeepLink(url: String) {
        if (!initialized) return
        val rsclid = try {
            Uri.parse(url).getQueryParameter("rsclid")
        } catch (e: Exception) {
            null
        }
        if (rsclid.isNullOrEmpty()) return
        val body = baseBody()
            .put("os", "Android")
            .put("event_type", "app_open")
            .put("install_referrer", "rsclid=$rsclid") // server lifts it like a Play referrer
        DeviceInfo.describe(appContext, body)
        transport.send("/api/tracking/mobile/first-open", body)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun reportFirstOpen(customerUserId: String?) {
        // Read the GAID (blocking) and the install referrer (async) off the main
        // thread, then post one first-open carrying both.
        transport.background {
            val gaid = DeviceId.advertisingId(appContext)
            // Fraud/dedup only, never attribution — see AppSetId's docs on
            // Google's policy. Blocking, hence out here beside the GAID read.
            val appSetId = AppSetId.fetch(appContext)
            InstallReferrerReader.fetch(appContext) { result ->
                val body = baseBody()
                    .put("os", "Android")
                    .put("app_version", appVersion())
                    // Sent even on failure — this is what makes "why did this
                    // device never get a referrer" answerable from the backend
                    // instead of needing that device's own Logcat.
                    .put("referrer_status", result.status)
                    // The install IS session 1's opening touch, so it carries the
                    // session's pv_id: the closing beacon then folds foreground
                    // time into this row instead of writing an app_open next to it.
                    .put("pv_id", storage.sessionPvId)
                    .put("session_number", storage.sessionNumber)
                // Model, OS/API level, Play Store version, form factor, installer.
                // Sets device_type too — which is why it is no longer the constant
                // "mobile" that made every tablet look like a phone.
                DeviceInfo.describe(appContext, body)
                gaid?.let { body.put("device_id", it) } // raw; the server hashes it
                appSetId?.let { body.put("app_set_id", it) } // raw; the server SALT-hashes it
                result.referrer?.let { body.putReferrer(it) }
                customerUserId?.let { body.put("external_id", it) }
                transport.send("/api/tracking/mobile/first-open", body)

                // The install is now on record either way — losing an install
                // count would be worse than losing its referrer — so this stays
                // true unconditionally. What is NEW is remembering that the
                // referrer is still owed to us when the failure was transient;
                // previously that was indistinguishable from a clean read and
                // the device never tried again for the life of the install.
                storage.installReported = true
                storage.referrerPending =
                    result.referrer == null && InstallReferrerReader.isTransient(result.status)
                storage.referrerAttempts = 0
            }
        }
    }

    /**
     * Try the Play referrer again on a later launch, after a transient failure
     * on first open.
     *
     * The recovered referrer is sent as an **app_open**, not a second
     * first-open: the install touchpoint already exists, and writing a duplicate
     * would inflate install counts and split multi-touch credit across two
     * phantom touches. An app_open carrying the rsclid is the same shape
     * [handleDeepLink] already uses, and the attribution waterfall picks the
     * click id up from it via the vid exactly the same way.
     */
    private fun retryReferrer() {
        val attempts = storage.referrerAttempts
        if (attempts >= MAX_REFERRER_ATTEMPTS) {
            storage.referrerPending = false
            return
        }
        storage.referrerAttempts = attempts + 1
        transport.background {
            InstallReferrerReader.fetch(appContext) { result ->
                val referrer = result.referrer
                if (referrer != null) {
                    val body = baseBody()
                        .put("os", "Android")
                        .put("event_type", "app_open")
                        .put("app_version", appVersion())
                        // Distinct from a first-launch "OK" on purpose: a
                        // recovered referrer and a clean one attribute the same,
                        // but only one of them says the first read failed.
                        .put("referrer_status", "RETRY_${result.status}")
                    DeviceInfo.describe(appContext, body)
                    body.putReferrer(referrer)
                    transport.send("/api/tracking/mobile/first-open", body)
                    storage.referrerPending = false
                } else if (!InstallReferrerReader.isTransient(result.status)) {
                    storage.referrerPending = false // permanent — stop asking
                }
            }
        }
    }

    /** The referrer fields, written the same way from both the first-open and
     *  the retry path so the two can never drift. */
    private fun JSONObject.putReferrer(referrer: InstallReferrerReader.Referrer) {
        put("install_referrer", referrer.referrer)
        referrer.clickToInstallSeconds?.let { put("click_to_install_seconds", it) }
        // The endpoints behind that gap. Sent as epoch SECONDS (Play's own unit);
        // the server parses and range-checks them.
        referrer.clickTimestampSeconds?.let { put("referrer_click_timestamp", it) }
        referrer.installBeginTimestampSeconds?.let { put("install_begin_timestamp", it) }
    }

    /**
     * Fields every beacon carries.
     *
     * `sequence` is minted here, per beacon, and that is the point: beacons are
     * fire-and-forget and race each other, so the order they land in is not the
     * order they happened in. A server timestamp would reconstruct the wrong
     * path — a checkout appearing before the product view that led to it.
     *
     * `pv_id` is deliberately NOT here. It belongs only on the two touchpoint
     * beacons that open and close a session; putting it on every beacon would
     * make identify/track calls upsert onto the session's touch instead of
     * recording themselves.
     */
    private fun baseBody(): JSONObject =
        JSONObject()
            .put("site", publicKey)
            .put("vid", storage.visitorId)
            .put("session_id", sessions.current().id)
            .put("sequence", sessions.nextSequence())

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
