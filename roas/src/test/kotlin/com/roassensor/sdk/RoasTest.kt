package com.roassensor.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Behavioural tests for [Roas] — the single most complex, most bug-prone file
 * in this SDK (multiple real production regressions already traced to it; see
 * the changelog in build.gradle.kts). It had zero automated coverage before
 * this file.
 *
 * Deliberately NOT covered here: the fresh-install path through
 * [Roas.initialize] → `reportFirstOpen` → [InstallReferrerReader.fetch]. That
 * reads the real `com.android.installreferrer` client, which binds an actual
 * Android service — there is no Robolectric shadow for it, and driving it
 * under test would mean either hanging on a service that never connects or
 * hand-rolling a fake that could silently drift from the real client's
 * behaviour. [InstallReferrerStatusTest] already covers the pure decision
 * logic (`classify`/`isTransient`) that path depends on. Every test here
 * instead starts from `installReported = true` (a returning user / an app
 * that has already been through first-open), which is also exactly the state
 * a real device is in for every launch after the first — the lifecycle and
 * session bugs this file exists to catch only manifest on THOSE launches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoasTest {

    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private var activityController: ActivityController<*>? = null

    @Before
    fun setUp() {
        // Roas is a process-wide singleton; Robolectric reuses one JVM/classloader
        // across the @Test methods in this class, so without this every test after
        // the first would see initialize() no-op on the `initialized` guard left set
        // by whichever test ran before it. See Roas.resetForTests's doc comment.
        Roas.resetForTests()
        server = MockWebServer()
        server.start()
        for (i in 0 until 20) server.enqueue(MockResponse().setResponseCode(201))
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        activityController?.destroy()
        Roas.resetForTests()
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    /** A stand-in for a real device's `PackageManager.firstInstallTime` — an
     *  arbitrary but realistic (positive, epoch-ms) value. Robolectric
     *  defaults every package's `firstInstallTime` to 0 with no shadow
     *  configuration, which [Roas.resetIfDataWasResurrected] deliberately
     *  never trusts as real (a genuine device value is always positive), so
     *  tests that exercise that check need [setDeviceFirstInstallTime]. */
    private val realisticInstallTime = 1_700_000_000_000L

    /** Sets the `firstInstallTime` Robolectric's PackageManager reports for
     *  THIS app's own package, via its shadow — there is no public Android
     *  API to set it (the OS assigns it at real install time), so a test
     *  that wants Roas to see a specific value has to configure the shadow. */
    private fun setDeviceFirstInstallTime(value: Long) {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.firstInstallTime = value
        org.robolectric.Shadows.shadowOf(app.packageManager).installPackage(info)
    }

    /** Marks this "device" as already past first-open, the state every launch
     *  but the very first is in — including a [Storage.firstInstallTime] that
     *  matches the (realistic, non-zero) value the OS reports, so [Roas]'s
     *  OS-reinstall detection doesn't mistake this seed for resurrected data
     *  and force the (un-mockable) fresh-install path. */
    private fun seedReturningInstall() {
        setDeviceFirstInstallTime(realisticInstallTime)
        val storage = Storage(app)
        storage.installReported = true
        storage.firstInstallTime = realisticInstallTime
    }

    /** A session recorded as having gone idle more than 30 minutes ago, so the
     *  next [SessionTracker.current] call rolls it over rather than resuming it. */
    private fun seedExpiredSession() {
        val storage = Storage(app)
        storage.sessionId = "stale-session"
        storage.sessionNumber = 3
        storage.sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("expected a request but none arrived")

    private fun takeRequestBody(): JSONObject = JSONObject(takeRequest().body.readUtf8())

    private fun assertNoMoreRequests() {
        assertNull("expected no further requests", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    /**
     * [Roas.sendSessionStart] always fires the app_open beacon immediately
     * followed by a best-effort same-IP deferred-link probe — drain and sanity
     * check both together so individual tests don't need to know that pairing.
     * Returns the app_open body, since that's what most callers care about.
     */
    private fun drainSessionStart(): JSONObject {
        val appOpen = takeRequest()
        assertEquals("/api/tracking/mobile/first-open", appOpen.path)
        val appOpenBody = JSONObject(appOpen.body.readUtf8())
        assertEquals(TouchPointEventType.APP_OPEN, appOpenBody.getString("event_type"))

        val deferred = takeRequest()
        assertEquals("/api/tracking/mobile/deferred-link", deferred.path)
        val deferredBody = JSONObject(deferred.body.readUtf8())
        assertEquals("site-key", deferredBody.getString("site"))
        assertTrue(deferredBody.has("vid"))
        // Minimal on purpose — CollectSerializer only requires site+vid, and a
        // fire-and-forget probe has no business carrying device/session fields.
        assertFalse(deferredBody.has("event_type"))

        return appOpenBody
    }

    // ── handleDeepLink: the path the deep-link → DB parameters travel through ──

    @Test
    fun `handleDeepLink with an rsclid sends an app_open carrying the FULL query as install_referrer`() {
        seedReturningInstall()
        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())
        drainSessionStart() // initialize()'s own session-start pair

        Roas.handleDeepLink(
            "https://example.com/open?rsclid=AbC123&utm_source=meta&rs_campaign=summer_sale",
        )

        val body = takeRequestBody()
        assertEquals("site-key", body.getString("site"))
        assertEquals("Android", body.getString("os"))
        assertEquals(TouchPointEventType.APP_OPEN, body.getString("event_type"))
        // The FULL query string, not just rsclid — a live emulator test caught
        // an earlier version of this method dropping utm_source/rs_campaign,
        // which services/ingest.py is fully able to extract if given the chance.
        assertEquals(
            "rsclid=AbC123&utm_source=meta&rs_campaign=summer_sale",
            body.getString("install_referrer"),
        )
        // Device context must ride along too, or the backend can't diagnose a
        // deep-link install the way it can a Play-referrer one.
        assertTrue(body.has("device_type"))
        assertTrue(body.has("os_version"))
        assertNoMoreRequests()
    }

    @Test
    fun `handleDeepLink with a non-rsclid click id (gclid) is still forwarded`() {
        // Gating on rsclid specifically used to drop a marketer's own
        // Google/Meta deep link outright, even though CLICK_ID_PARAMS on the
        // backend already recognizes gclid/fbclid/etc. — the SDK has no
        // business deciding which click-id vocabulary is "real".
        seedReturningInstall()
        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())
        drainSessionStart()

        Roas.handleDeepLink("https://example.com/open?gclid=xyz789&utm_source=google")

        val body = takeRequestBody()
        assertEquals("gclid=xyz789&utm_source=google", body.getString("install_referrer"))
        assertNoMoreRequests()
    }

    @Test
    fun `handleDeepLink with no query string at all is a no-op`() {
        seedReturningInstall()
        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())
        drainSessionStart() // the session-start pair from initialize()

        Roas.handleDeepLink("https://example.com/open")

        assertNoMoreRequests()
    }

    @Test
    fun `handleDeepLink before initialize is a no-op, not a crash`() {
        // No Roas.initialize() call at all in this test.
        Roas.handleDeepLink("https://example.com/open?rsclid=AbC123")
        assertNoMoreRequests()
    }

    // ── Returning-user session-start + idempotent initialize ───────────────────

    @Test
    fun `a returning user with an expired session gets exactly one session-start pair`() {
        seedReturningInstall()
        seedExpiredSession()

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        val body = drainSessionStart()
        assertEquals(4, body.getInt("session_number")) // rolled over from the seeded 3
        assertNoMoreRequests()
    }

    @Test
    fun `a second initialize call in the same process is a no-op`() {
        seedReturningInstall()
        seedExpiredSession()

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())
        drainSessionStart() // the one legitimate session-start pair

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        assertNoMoreRequests()
    }

    // ── App-open deferred-match (Roas.kt sendSessionStart) ─────────────────────

    @Test
    fun `session-start's deferred-link probe carries this install's own vid`() {
        seedReturningInstall()
        seedExpiredSession()

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        takeRequest() // app_open
        val deferred = JSONObject(takeRequest().body.readUtf8())
        assertEquals(Roas.visitorId(), deferred.getString("vid"))
        assertNoMoreRequests()
    }

    // ── Late legacy-broadcast referrer (Roas.kt checkForLateBroadcastReferrer) ─
    //
    // See InstallReferrerBroadcastReceiverTest for the receiver itself and
    // ReferrerFallbackTest for the priority logic; these cover Roas picking a
    // late-arriving value up on the NEXT launch, the exact race the broadcast
    // can lose against the very first install beacon.

    @Test
    fun `a broadcast referrer that arrived after the install beacon is sent on the next launch`() {
        seedReturningInstall()
        seedExpiredSession()
        val storage = Storage(app)
        storage.awaitingBroadcastReferrer = true
        storage.broadcastReferrer = "utm_source=vivo_store&rs_campaign=summer_sale"

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        drainSessionStart() // the ordinary session-start pair fires first
        val late = takeRequestBody()
        assertEquals(TouchPointEventType.APP_OPEN, late.getString("event_type"))
        assertEquals("OK_BROADCAST_LATE", late.getString("referrer_status"))
        assertEquals("utm_source=vivo_store&rs_campaign=summer_sale", late.getString("install_referrer"))
        assertNoMoreRequests()

        assertFalse(Storage(app).awaitingBroadcastReferrer)
        assertTrue(Storage(app).broadcastReferrer.isEmpty()) // consumed
    }

    @Test
    fun `awaiting a broadcast that never arrives gives up quietly after one check`() {
        seedReturningInstall()
        seedExpiredSession()
        Storage(app).awaitingBroadcastReferrer = true
        // broadcastReferrer left empty — it never showed up.

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        drainSessionStart() // only the ordinary session-start pair — no late beacon
        assertNoMoreRequests()
        assertFalse(Storage(app).awaitingBroadcastReferrer)
    }

    @Test
    fun `awaitingBroadcastReferrer false is a complete no-op, no extra request`() {
        seedReturningInstall()
        seedExpiredSession()
        Storage(app).awaitingBroadcastReferrer = false

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        drainSessionStart()
        assertNoMoreRequests()
    }

    // ── OS-reinstall detection (Roas.kt resetIfDataWasResurrected) ─────────────
    //
    // Confirmed necessary by a live Vivo device test: uninstalling and
    // reinstalling the sample app through the on-device UI kept handing back
    // the SAME vid and installReported=true every time — some OEM data
    // retention layer preserved this SDK's SharedPreferences file across what
    // was, at the OS level, a genuine new package install. These tests don't
    // drive the full fresh-install beacon (that needs the un-mockable
    // InstallReferrerReader, see the class doc) — they assert the
    // OBSERVABLE side effect that matters: the stale identity is gone by the
    // time initialize() returns.

    @Test
    fun `private data whose firstInstallTime no longer matches the OS is wiped on initialize`() {
        setDeviceFirstInstallTime(realisticInstallTime) // what the OS says NOW
        val storage = Storage(app)
        storage.installReported = true
        val staleVid = storage.visitorId // mint + read the "resurrected" vid
        storage.firstInstallTime = realisticInstallTime - 1 // what was stored — doesn't match

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        assertNotEquals(staleVid, Roas.visitorId())
        assertFalse(Storage(app).installReported)
    }

    @Test
    fun `data with a NEVER recorded firstInstallTime is treated as resurrected too`() {
        // firstInstallTime defaults to 0L for data seeded before this check
        // existed (an app already in the field on an older SDK version) —
        // must not be trusted just because it matches nothing to compare
        // against. The OS itself always reports a real, positive value.
        setDeviceFirstInstallTime(realisticInstallTime)
        val storage = Storage(app)
        storage.installReported = true
        val staleVid = storage.visitorId
        // storage.firstInstallTime left at its 0L default on purpose.

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        assertNotEquals(staleVid, Roas.visitorId())
    }

    @Test
    fun `a genuinely matching firstInstallTime is left alone`() {
        seedReturningInstall() // seeds a real, matching firstInstallTime
        val realVid = Storage(app).visitorId

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())

        assertEquals(realVid, Roas.visitorId())
        assertTrue(Storage(app).installReported)
    }

    // ── The activity-lifecycle-counter regression (Roas.kt registerLifecycle) ──
    //
    // Documented in Roas.kt as a real production bug: a Flutter app calls
    // Roas.initialize() from Dart's main(), which the engine runs INSIDE
    // FlutterActivity.onStart() — i.e. after the first Activity.onStart has
    // already fired, which is a launch order a pure-Kotlin app (initialize()
    // from Application.onCreate()) never produces. Unclamped, that missed
    // start poisoned the counter and onEnterForeground never fired again for
    // the life of the process. This test reproduces that exact ordering.

    @Test
    fun `onEnterForeground still fires when initialize runs after the first Activity has already started`() {
        seedReturningInstall()

        // Start an Activity BEFORE Roas.initialize() — the Flutter host ordering.
        activityController = Robolectric.buildActivity(android.app.Activity::class.java)
        // Full lifecycle, not just start(): Robolectric's ActivityController only
        // dispatches onActivityStarted/onActivityStopped reliably across a real
        // create->start->resume->pause->stop transition sequence.
        activityController!!.create().start().resume()

        Roas.initialize(app, publicKey = "site-key", baseUrl = baseUrl())
        drainSessionStart() // initialize()'s own session-start pair (no live session yet)

        // Now background and re-foreground the app — the scenario that used to
        // never fire onEnterForeground under this exact ordering.
        activityController!!.pause().stop() // background: engagement app_open (upsert) beacon
        val backgroundBeacon = takeRequestBody()
        assertEquals(TouchPointEventType.APP_OPEN, backgroundBeacon.getString("event_type"))
        assertTrue(backgroundBeacon.has("engagement_ms"))

        // Simulate the app having sat backgrounded past the 30-minute idle
        // window, so the resumed session is genuinely new and onEnterForeground
        // has something to report — a session resumed WITHIN its window
        // legitimately sends nothing, which would make this assertion
        // indistinguishable from the callback never firing at all.
        Storage(app).sessionLastActiveAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31)

        activityController!!.start().resume() // foreground again
        val foregroundBeacon = drainSessionStart()
        assertEquals(
            "onEnterForeground must still fire after the Flutter-style start ordering",
            TouchPointEventType.APP_OPEN,
            foregroundBeacon.getString("event_type"),
        )
        assertNoMoreRequests()
    }

    /** Names used across these tests, matching `TouchPoint.EventType` on the
     *  backend (models.py) so a drift there is visible here too. */
    private object TouchPointEventType {
        const val APP_OPEN = "app_open"
    }
}
