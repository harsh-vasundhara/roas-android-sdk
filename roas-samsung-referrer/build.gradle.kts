// Optional add-on to :roas — reads Samsung Galaxy Store's own install-referrer
// channel. Same shape as :roas-xiaomi-referrer; see
// com.roassensor.sdk.SamsungReferrerBridge's doc comment in :roas for the
// full reasoning. Opt-in only: apps that don't target the Samsung market
// never pay for `samsung_galaxystore_install_referrer`.
plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "com.roassensor.sdk.samsung"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
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
}

dependencies {
    compileOnly(project(":roas"))
    // Samsung's own official install-referrer library, published on Maven
    // Central — structurally near-identical to Google's own client (same
    // class/method shapes, different package), which is why
    // SamsungReferrerBridgeImpl.kt reads almost like InstallReferrerReader.kt
    // with the import swapped.
    implementation("store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0")

    testImplementation("junit:junit:4.13.2")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )
    coordinates(
        groupId = "com.roassensor",
        artifactId = "roas-samsung-referrer",
        version = "0.1.0",
    )
    pom {
        name.set("ROASSensor Android SDK — Samsung referrer")
        description.set(
            "Optional add-on for com.roassensor:roas that reads Samsung Galaxy " +
                "Store's own install-referrer channel. Add alongside the core SDK " +
                "only if you target the Samsung market."
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
