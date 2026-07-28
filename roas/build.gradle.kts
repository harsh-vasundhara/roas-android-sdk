plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    // Pinned below latest (0.37.0 requires AGP 8.13+/Gradle 9.0+; this project
    // is on AGP 8.5.0/Gradle 8.12.1 — see gradle-wrapper.properties for why
    // Gradle is pinned pre-9.x). 0.34.0's minimums (Gradle 8.5, AGP 8.0.0) fit.
    id("com.vanniktech.maven.publish") version "0.34.0"
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
    // NOTE: no manual `publishing { singleVariant(...) }` here — the
    // com.vanniktech.maven.publish plugin auto-detects the Android Library
    // plugin and configures the "release" variant + sources jar itself.
    // Declaring it here too fails with "Using singleVariant publishing DSL
    // multiple times ... is not allowed."
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

// Maven Central publishing. Credentials/signing key live in the *global*
// ~/.gradle/gradle.properties — never in this file or the repo. See
// roas-android-sdk/README.md "Publishing" section for the one-time setup
// (GPG key, Central Portal token) and `./gradlew :roas:publishAndReleaseToMavenCentral`
// to actually publish a release.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.roassensor", "roas", "0.1.0")

    pom {
        name.set("ROASSensor Android SDK")
        description.set(
            "Native Android tracking for ROASSensor — install attribution (Google Play " +
                "Install Referrer), funnel events, and identity, with revenue kept out of " +
                "the app (RevenueCat/Stripe webhook only)."
        )
        url.set("https://github.com/harsh-vasundhara/roas-android-sdk")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/harsh-vasundhara/roas-android-sdk/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("vasundhara")
                name.set("Vasundhara Infotech LLP")
                url.set("https://vasundharasolutions.com")
            }
        }
        scm {
            url.set("https://github.com/harsh-vasundhara/roas-android-sdk")
            connection.set("scm:git:git://github.com/harsh-vasundhara/roas-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/harsh-vasundhara/roas-android-sdk.git")
        }
    }
}
