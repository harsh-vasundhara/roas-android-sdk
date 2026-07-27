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
        // Testing the hosted SDK build — remove once Maven Central is live.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "roas-android"
include(":roas")
// A tiny runnable app that uses the SDK — for testing on a real device.
include(":sample")
