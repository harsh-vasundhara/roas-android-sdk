import java.util.Properties

plugins {
    id("com.android.application") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

// Release signing is optional and local-only: see keystore.properties.example.
// Without a keystore.properties on disk, `assembleRelease`/`bundleRelease` still
// runs, just unsigned — Play Console needs a real signature before it'll accept
// an upload, but this keeps the build from breaking on a fresh clone.
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Backend URL + site keys for THIS developer's test setup, from roas.properties
// (gitignored; see roas.properties.example). They reach MainActivity as
// BuildConfig constants so a LAN IP or a site key is never hardcoded in source —
// the previous hardcoded values went stale the moment the PC changed networks
// and pointed every fresh clone at a backend that no longer existed.
val roasPropertiesFile = file("roas.properties")
val roasProperties = Properties().apply {
    if (roasPropertiesFile.exists()) {
        roasPropertiesFile.inputStream().use { load(it) }
    }
}
fun roasProp(key: String, default: String = ""): String =
    (roasProperties.getProperty(key) ?: default).trim()
// A Kotlin string literal for BuildConfig — quoted and escaped, never raw.
fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.roassensor.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.roassensor.sample"
        // 23, not the library's 21: com.revenuecat.purchases:purchases requires minSdk 23.
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ROAS_BASE_URL", quoted(roasProp("roas.baseUrl", "http://10.0.2.2:8000")))
        buildConfigField("String", "ROAS_PUBLIC_KEY", quoted(roasProp("roas.publicKey")))
        buildConfigField("String", "ROAS_APP_SECRET", quoted(roasProp("roas.appSecret")))
        buildConfigField("String", "REVENUECAT_API_KEY", quoted(roasProp("roas.revenueCatApiKey")))
    }
    buildFeatures {
        // AGP 8 no longer generates BuildConfig unless asked.
        buildConfig = true
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    implementation(project(":roas")) // the SDK under test
    // Local-only, pre-publish testing of the two optional OEM referrer
    // modules — a real host app would instead add
    // implementation("com.roassensor:roas-xiaomi-referrer:...") once these
    // are published. project() deps work here because this module lives in
    // the same Gradle build; :roas core still only ever reaches them
    // reflectively, so this is purely how the sample app gets them onto its
    // OWN classpath for a real device to exercise.
    implementation(project(":roas-xiaomi-referrer"))
    implementation(project(":roas-samsung-referrer"))
    implementation("com.revenuecat.purchases:purchases:10.15.1")
}
