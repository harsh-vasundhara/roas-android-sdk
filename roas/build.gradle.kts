plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
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
    // The SDK ships as a source-available AAR; keep the public API stable.
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
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
