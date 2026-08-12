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
        version = "0.1.5",
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
