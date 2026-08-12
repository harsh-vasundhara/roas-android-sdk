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
    implementation("com.revenuecat.purchases:purchases:10.15.1")
}
