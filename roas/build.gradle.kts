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
        version = "0.1.4",
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
