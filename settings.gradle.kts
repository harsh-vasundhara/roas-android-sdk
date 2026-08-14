// ROASSensor Android SDK — Gradle settings.
// A single library module (`:roas`) published as an AAR the customer's app
// depends on. Open this folder in Android Studio, or build with `./gradlew :roas:assembleRelease`.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "roas-android"
include(":roas")
// A tiny runnable app that uses the SDK — for testing on a real device.
include(":sample")
// Optional add-ons: :roas core reaches these only reflectively, so a host
// app that doesn't add them (most apps) never pays for either vendor's
// library. See com.roassensor.sdk.XiaomiReferrerBridge/SamsungReferrerBridge
// in :roas for the full reasoning.
include(":roas-xiaomi-referrer")
include(":roas-samsung-referrer")
