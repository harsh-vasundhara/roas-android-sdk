plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    // Real automated Central Portal publishing — signing.* / mavenCentral*
    // properties in ~/.gradle/gradle.properties already exist (used to get
    // 0.1.0 onto Central) but nothing in this build ever consumed them; the
    // 0.1.0 publish must have happened some other way. This plugin is the
    // one whose property names those already match (signingInMemoryKey /
    // signingInMemoryKeyId / signingInMemoryKeyPassword / mavenCentralUsername
    // / mavenCentralPassword), so it's what was actually intended here. It
    // applies `maven-publish` itself — no need to also request that plugin.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "com.roassensor.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21 // Android 5.0 — covers ~99% of devices
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    // Variant publishing is configured below via mavenPublishing's own
    // AndroidSingleVariantLibrary — NOT here too. The vanniktech plugin
    // calls `singleVariant("release")` itself; declaring it a second time
    // here fails with "Using singleVariant publishing DSL multiple times".
}

dependencies {
    // The Google Play Install Referrer — the whole point of deterministic Android
    // attribution. Small, first-party, no transitive bloat.
    implementation("com.android.installreferrer:installreferrer:2.2")
    // GAID (advertising id). Optional at runtime: if the host app doesn't include
    // Play Services, DeviceId degrades to "no ad id" rather than crashing — we read
    // it reflectively. Declared `compileOnly` so we don't force the dependency on
    // apps that don't want it.
    compileOnly("com.google.android.gms:play-services-ads-identifier:18.0.1")

    // Pure-JVM unit tests (Hashing has no Android deps) — verifies byte-for-byte
    // parity with the backend using vectors generated from security.py.
    testImplementation("junit:junit:4.13.2")

    // Robolectric runs Android framework classes (Context, SharedPreferences,
    // Application.ActivityLifecycleCallbacks, Resources/Locale/TimeZone) on the
    // JVM, which is what RoasTest needs: Roas.kt's install/session/lifecycle
    // logic is the most complex, most bug-prone surface in this SDK (two real
    // production regressions already traced to it — see the 0.1.4 changelog
    // above) and had zero automated coverage before this.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    // A real local HTTP server for Transport to POST to, so tests exercise the
    // actual HttpURLConnection path end-to-end (request body, headers, retry
    // behaviour) instead of a hand-rolled Transport fake that could silently
    // drift from what the real one does.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Publishing to Maven Central (live since 2026-07-28 as com.roassensor:roas).
// The vanniktech plugin creates and configures the "release" publication
// itself from the AGP release component — a hand-registered MavenPublication
// (the previous approach here) would fight it for the same publication name.
mavenPublishing {
    // Explicit, not the default: `createStagingRepository` (Nexus/legacy-OSSRH
    // terminology) firing instead of a Central Portal deployment call is what
    // gave away that omitting the host arg was NOT defaulting to Central
    // Portal here — it was hitting the legacy OSSRH staging host, whose TLS
    // chain this JVM doesn't trust (unrelated to and not fixable via the
    // mavenCentralUsername/Password tokens, which are Central Portal
    // credentials, not legacy OSSRH ones).
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    // Replaces the old `android { publishing { singleVariant(...) } } }`
    // block — this is the Android-library-specific config the plugin
    // expects, and it also generates the javadoc jar Central requires
    // (empty is fine; there's no Dokka setup here) instead of needing one
    // hand-built with `jar cf`.
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )

    coordinates(
        groupId = "com.roassensor",
        artifactId = "roas",
        // Bumped from 0.1.0 for the RoasLogLevel/handleDeepLink/
        // setLogLevel additions — republishing the SAME version to
        // mavenLocal left Gradle's dependency/transform cache serving the
        // stale pre-change AAR even after --refresh-dependencies; a real
        // version bump is the reliable way to force every consumer to
        // actually pick up new SDK code, not just a workaround for this
        // one case.
        //
        // 0.1.2: device context on every install (model / API level / Play
        // Store version / real form factor) + the transient-referrer retry.
        // Keep DeviceInfo.SDK_VERSION in step — it is what the backend
        // stores, so a mismatch makes rows trace to the wrong build.
        //
        // 0.1.4: two release-only/host-only bugs found by testing rather than
        // reading, both invisible in a debug build on a Kotlin app:
        //   * the activity counter poisoned itself on any host that starts the
        //     SDK after the first activity (i.e. every Flutter app), so
        //     onEnterForeground stopped firing — no session-start beacons for
        //     returning users, frozen engagement_ms. See registerLifecycle.
        //   * consumer-rules.pro never kept the App Set ID classes, so R8
        //     renamed them and app_set_id was blank in every minified build.
        //
        // 0.1.5: a batch of fixes from a live testing pass (real emulator +
        // two physical devices — a Nokia and a Vivo V2130 — plus a real
        // /c/<slug> ad click), all found and fixed together:
        //   * handleDeepLink forwarded ONLY rsclid, dropping every other
        //     query param — caught by firing
        //     `roassample://open?rsclid=X&utm_source=meta&rs_campaign=...`
        //     and finding utm_source/rs_campaign missing from the resulting
        //     TouchPoint. It also refused to forward a link using a
        //     non-rsclid click id (gclid/fbclid/...) at all, even though
        //     CLICK_ID_PARAMS on the backend already recognizes those. Now
        //     forwards the full raw query string whenever one is present,
        //     the same as a Play install referrer.
        //   * An OEM data-retention layer (or Android's own Auto Backup) can
        //     preserve this SDK's own SharedPreferences file across what the
        //     OS treats as a genuine reinstall — confirmed live on the Vivo:
        //     the same vid and installReported=true kept coming back across
        //     repeated on-device uninstall/reinstall cycles. Roas.initialize()
        //     now compares PackageManager.firstInstallTime (which lives
        //     outside the app's private data dir, so no data-clone/restore
        //     can fake it) against the last value it saw, and wipes the
        //     install/vid/session state on a mismatch. See
        //     Storage.firstInstallTime. Storage.resetForNewInstall also
        //     clears the beacon queue when this fires — a queued entry's
        //     JSON body has the OLD vid baked in at enqueue time, and
        //     delivering it after the reset would attach a stale identity's
        //     beacon to what is now a different install.
        //   * An already-installed user who taps an ad link produced a
        //     completely unattributed app_open — confirmed live: a real
        //     tracking-link click was logged correctly, then the resulting
        //     app_open 38 seconds later carried zero campaign data, because
        //     nothing ever calls MobileDeferredLinkView outside of a fresh
        //     install. sendSessionStart now fires a best-effort probe to it
        //     on every session start.
        //   * That probe's own beacon surfaced a genuine concurrency bug:
        //     Transport.flush() read the queue, spent real time on network
        //     I/O, then overwrote the queue with a now-stale "remaining"
        //     snapshot — silently discarding any beacon enqueued by a second
        //     send() fired moments later (which is exactly what the
        //     deferred-link probe does, right after the app_open send).
        //     Storage.removeDelivered replaces that read-then-overwrite
        //     pattern with a single atomic read-modify-write, so a beacon
        //     enqueued mid-flush now survives.
        //   * Install attribution for non-Play-Store installs. Adjust's
        //     Android SDK (external comparison, this repo not affiliated)
        //     still listens for the legacy `INSTALL_REFERRER` broadcast
        //     several OEM stores (Huawei AppGallery, Xiaomi GetApps, Vivo App
        //     Store, Samsung Galaxy Store, Amazon Appstore) send for
        //     compatibility with older marketing SDKs — we only ever asked
        //     Google's newer Install Referrer API, which answers
        //     FEATURE_NOT_SUPPORTED for any non-Play install and nothing
        //     else, indistinguishable from organic. Added
        //     InstallReferrerBroadcastReceiver + ReferrerFallback (Play's own
        //     answer always wins when it has one; the broadcast is a
        //     fallback only for Play's total silence) + a one-shot
        //     next-launch catch-up for when the broadcast arrives a few
        //     seconds after the very first install beacon already went out.
        //     Confirmed live on the Vivo via a real `adb shell am broadcast`.
        //
        // 0.1.6: multi-device real testing this round (Xiaomi, Nokia/Android
        // One, Samsung, realme, OnePlus, HUAWEI, stock Pixel, two vivo
        // models) found Google's own Install Referrer API answering
        // OK_NOT_SET even seconds after a real, matched ad click, on every
        // brand tested except one specific vivo unit — Google's channel
        // alone is not reliable enough on real Android hardware. Adjust's
        // SDK (external comparison) reads each OEM's own independent
        // referrer channel in addition to Google's, because Vivo/Huawei/
        // Xiaomi/Samsung all track installs on their own skinned builds for
        // their own analytics. Added the same, matched to this SDK's own
        // conventions:
        //   * Vivo and Huawei readers live directly in this module — both
        //     are a plain synchronous ContentProvider read (Vivo:
        //     ContentResolver.call, Huawei: ContentResolver.query), no
        //     external dependency, no timeout needed.
        //   * Xiaomi and Samsung need their own published client libraries
        //     (com.miui.referrer:homereferrer, Samsung's Galaxy Store
        //     install-referrer artifact) — async, Builder/Listener APIs
        //     structurally near-identical to Google's own. Rather than a
        //     hard dependency here (forcing every consumer to bundle both
        //     AARs regardless of market) or reflecting the vendor's own
        //     complex listener interface (fragile — a Proxy over a
        //     third-party callback breaks silently on any signature change),
        //     they live in two new optional modules
        //     (com.roassensor:roas-xiaomi-referrer,
        //     com.roassensor:roas-samsung-referrer) that reflect into this
        //     module's own small, stable OemReferrerCallback contract
        //     instead. A host app adds either only if it targets that
        //     market; :roas core stays exactly as dependency-light as
        //     before for everyone else.
        //   * Tried in OemDevice-matched order, only when Google's own
        //     answer wasn't usable (OK_NOT_SET/OK_EMPTY/a failure) — Google
        //     still always wins when it has a real answer, per
        //     ReferrerFallback's existing principle. The OEM channel
        //     outranks the legacy INSTALL_REFERRER broadcast fallback too:
        //     synchronous and same-request, vs. the broadcast's inherent
        //     race. New referrer_source field records which of the four (or
        //     "google"/"broadcast") actually answered, alongside the
        //     existing referrer_status.
        //   * Also fixed: an organic install (no real click) has Play/every
        //     OEM report a 0 click timestamp, and the old gap computation
        //     (install time minus click time) turned that into the raw
        //     install epoch disguised as a multi-decade "click-to-install"
        //     value — confirmed live on a vivo V2130 and a Pixel 7a. Now
        //     null whenever the click timestamp isn't a real positive value,
        //     matching how the backend already treated the two raw
        //     timestamps.
        //
        //   What was VERIFIED ON REAL HARDWARE before publishing, by
        //   temporarily forcing the OEM path to run regardless of Google's
        //   answer (the gating means it otherwise never fires while Google
        //   is answering normally), then reverting that override:
        //     * Xiaomi (Redmi 22101316I) — reflective bridge resolved, bound
        //       to GetApps' real client, returned REAL referrer data. That
        //       test is also what surfaced GetApps' own placeholder
        //       convention (`utm_medium=null`, distinct from Google's
        //       "(not set)"/"organic"), which the BACKEND did not recognize
        //       and would have leaked into dashboards as a fake channel —
        //       fixed in ingest.py, not here.
        //     * Samsung (SM-M326B, Galaxy Store present) — reflective bridge
        //       resolved, bound to Samsung's real InstallReferrerClient,
        //       answered OK with an empty referrer (correct: the test app was
        //       sideloaded, not installed from Galaxy Store) in 113ms, one
        //       clean callback, nowhere near the 5s timeout.
        //     * Vivo (V2142 AND V2130) — this is where forcing the path paid
        //       for itself. Both initially answered NOT_AVAILABLE and were
        //       briefly written off as "no Vivo store installed". Both in
        //       fact ship the store, and TWO stacked bugs hid it — either
        //       one alone would have made the Vivo AND Huawei readers
        //       dead code in every real app:
        //         - the store is packaged as com.vivo.apprecommend on these
        //           builds, not com.vivo.appstore, so its referrer provider
        //           lives at com.vivo.apprecommend.provider.referrer. A
        //           single hardcoded authority missed it entirely;
        //           VivoReferrerReader now tries a list.
        //         - this SDK's manifest never declared those providers in
        //           <queries>, so API 30+ package visibility hid them even
        //           with the right name. Diagnosed by `adb shell content
        //           call` (shell UID) answering while the identical call
        //           from inside the app did not. Xiaomi/Samsung were immune
        //           only because their vendor AARs declare their own
        //           visibility, which is why those two passed while these
        //           two silently did not.
        //       After both fixes the V2130 reports OK_EMPTY (store present,
        //       no referrer for a SIDELOADED app — correct) instead of
        //       NOT_AVAILABLE. That distinction is now enforced in both
        //       ContentProvider readers: NOT_AVAILABLE means "no store on
        //       this device" and nothing weaker, mirroring how the Google
        //       path already separates FEATURE_NOT_SUPPORTED from
        //       OK_NOT_SET.
        //   NOT verified on hardware: Huawei (no device available), and no
        //   OEM channel has yet been observed WINNING an attribution in
        //   production — that needs a real store-installed app with real ad
        //   clicks, i.e. the field, not a sideloaded sample.
        //
        //   Parameter/accuracy work landed in the same unpublished 0.1.6
        //   rather than bumping again — nothing has shipped to Central yet,
        //   and a version number per working session is how you end up
        //   explaining a 0.1.8 that never existed anywhere:
        //     * `ts` on EVERY beacon (baseBody). Nothing Android sent one
        //       before, so `ingest._occurred_at` always fell back to ingest
        //       time — meaning the persisted offline queue, whose entire
        //       purpose is that an install happening offline still reports,
        //       recorded that install on the day it was FLUSHED. Monday's
        //       install became Wednesday's, sliding out of its lookback
        //       window and off the campaign that earned it. Stamped with the
        //       server-corrected clock (Storage.clockOffsetSeconds), and the
        //       server still rejects anything >5min future / >90d old.
        //     * identify() now carries device_id. mobile._device_keys binds
        //       the ad id to the email/phone being identified, but could
        //       never do so from this path because it sent none — so that
        //       binding happened once at first-open and never again, and
        //       that single read is the one most likely to have returned
        //       null (ads personalization off, Play Services not ready).
        //     * Google's SERVER-side referrer timestamps
        //       (referrerClickTimestampServerSeconds / install equivalent),
        //       alongside — never replacing — the client pair. The
        //       click-injection check ran purely on device-clock values,
        //       i.e. was forgeable by setting the clock; Google's own
        //       timestamps never touch the attacker's device. Older Play
        //       builds omit them, which is normal rather than suspicious.
        //     * PackageManager firstInstallTime/lastUpdateTime (converted to
        //       SECONDS, since the backend guard rejects millis-shaped
        //       values) — a true install moment independent of beacon
        //       arrival, and first != last distinguishes a real first
        //       install from an update reporting for the first time.
        //     * network_type + is_vpn from ConnectivityManager, costing no
        //       new permission (ACCESS_NETWORK_STATE is already held for
        //       delivery). is_vpn is the load-bearing one: the deferred
        //       same-IP match is the only route by which a referrer-less
        //       install still attributes, and a VPN silently invalidates the
        //       IP it compares — previously with nothing in the row to say
        //       so. Deliberately tri-state; NULL means "never found out",
        //       which is not "no VPN".
        version = "0.1.6",
    )

    pom {
        name.set("ROASSensor Android SDK")
        description.set(
            "Native Android tracking for ROASSensor: install attribution (Play " +
                "Install Referrer), funnel events, and identity — hands the app a " +
                "visitor id to thread into RevenueCat so purchases attribute to " +
                "the exact ad that drove the install."
        )
        url.set("https://github.com/harsh-vasundhara/roas-sensor-service")
        licenses {
            license {
                name.set("Proprietary")
                url.set("https://roassensor.com")
            }
        }
        developers {
            developer {
                id.set("roassensor")
                name.set("ROAS Sensor")
                email.set("support@roassensor.com")
            }
        }
        scm {
            url.set("https://github.com/harsh-vasundhara/roas-sensor-service")
            connection.set("scm:git:git://github.com/harsh-vasundhara/roas-sensor-service.git")
            developerConnection.set("scm:git:ssh://github.com/harsh-vasundhara/roas-sensor-service.git")
        }
    }
}
