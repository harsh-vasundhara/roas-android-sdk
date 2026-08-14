// Optional add-on to :roas — reads Xiaomi GetApps' own install-referrer
// channel (independent of, and on real HyperOS/MIUI hardware often more
// reliable than, Google's Play Install Referrer API; see
// com.roassensor.sdk.XiaomiReferrerBridge's doc comment in :roas for the
// full reasoning). A host app opts in with its own
// `implementation("com.roassensor:roas-xiaomi-referrer:...")` line ON TOP
// of `com.roassensor:roas` — apps that don't target the Xiaomi/Redmi/POCO
// market never pay for `com.miui.referrer:homereferrer`, since :roas core
// only ever reaches this module reflectively and degrades cleanly when it's
// absent.
plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "com.roassensor.sdk.xiaomi"
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
    // OemReferrerCallback — the one :roas type this module implements to hand
    // results back to :roas core's reflective XiaomiReferrerBridge.
    // compileOnly because every consumer of this module already depends on
    // :roas directly; bundling a second copy would be exactly the
    // "transitive bloat" :roas's own build.gradle.kts already avoids.
    compileOnly(project(":roas"))
    // Xiaomi's own official install-referrer library, published on Maven
    // Central by Xiaomi — the whole reason this is its own opt-in module
    // rather than living in :roas core.
    implementation("com.miui.referrer:homereferrer:1.0.0.7")

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
        artifactId = "roas-xiaomi-referrer",
        version = "0.1.0",
    )
    pom {
        name.set("ROASSensor Android SDK — Xiaomi referrer")
        description.set(
            "Optional add-on for com.roassensor:roas that reads Xiaomi GetApps' " +
                "own install-referrer channel. Add alongside the core SDK only if " +
                "you target the Xiaomi/Redmi/POCO market."
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
