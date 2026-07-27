plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.roassensor.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roassensor.sample"
        // 23, not 21 (the `roas` module's own floor) — RevenueCat's AAR declares
        // minSdkVersion 23 and the manifest merger fails below that. Only this
        // test app needs the bump; the SDK itself still ships at 21 for
        // customers who never touch RevenueCat.
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Swapped from `project(":roas")` to the hosted JitPack coordinate —
    // proves the *published* artifact resolves and works, not just local source.
    // Revert to `project(":roas")` once back to normal local development.
    implementation("com.github.harsh-vasundhara:roas-android-sdk:5a38d82779")
    // RevenueCat's Android SDK — proves the purchase -> webhook -> our backend
    // path end to end. Optional at the tracking level: the ROASSensor buttons
    // above work with no RevenueCat key configured at all.
    implementation("com.revenuecat.purchases:purchases:10.15.1")
}
