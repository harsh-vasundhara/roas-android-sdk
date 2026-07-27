# ROASSensor Android SDK (`roas-android`)

Native Android tracking for ROASSensor. Reports app **installs** with deterministic
attribution (Google Play Install Referrer), **funnel events**, and **identity** —
and hands you a visitor id to thread into RevenueCat so **purchases** attribute to
the exact ad that drove the install.

It talks to the backend's `/api/tracking/mobile/*` endpoints (built in P0). Revenue
is **not** sent from the app — it enters only through the signed RevenueCat webhook,
so ROAS stays defensible.

## Install

Published as an AAR (module `:roas`), currently hosted on JitPack while Maven
Central publishing is being set up under the `com.roassensor` namespace. Once
that lands, this becomes `com.roassensor:roas:<version>` with no extra
repository line needed (`mavenCentral()` is already in every Android project).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```
```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.harsh-vasundhara:roas-android-sdk:main-SNAPSHOT")
}
```

Requires `minSdk 21`. Play Services (`play-services-ads-identifier`) is optional —
without it the SDK still works, just referrer-only (no GAID). If you also add
RevenueCat (below), note its AAR requires `minSdk 23` — bump your app's `minSdk`
accordingly; this SDK's own floor stays 21 for apps that skip RevenueCat.

## Usage

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Roas.initialize(this, publicKey = "YOUR-SITE-PUBLIC-KEY")
    }
}
```

That single call, on first launch, reads the Play install referrer + GAID and
reports the install. Then:

```kotlin
// Identify the user when known (email/phone hashed on-device; raw never sent)
Roas.identify(email = "buyer@example.com")

// Funnel events (never revenue)
Roas.track(RoasEvent.ADD_TO_CART, properties = mapOf("sku" to "ABC", "qty" to 1))
Roas.track(RoasEvent.CUSTOM, name = "boss_defeated")

// Attribute purchases: pass the vid to RevenueCat as the app user id.
// Requires "com.revenuecat.purchases:purchases:10.15.1" (or later) and,
// as above, minSdk 23+ in the app consuming this.
Purchases.logLevel = LogLevel.DEBUG // useful while wiring this up; drop in release
Purchases.configure(
    PurchasesConfiguration.Builder(this, revenueCatApiKey)
        .appUserID(Roas.visitorId())
        .build()
)

// Later, to actually test a purchase (needs a real Play Console product
// synced to RevenueCat — see sample/MainActivity.kt for the full, verified
// fetch-offerings + purchase flow):
Purchases.sharedInstance.getOfferingsWith(
    onError = { error -> /* ... */ },
    onSuccess = { offerings ->
        val pkg = offerings.current?.availablePackages?.firstOrNull() ?: return@getOfferingsWith
        Purchases.sharedInstance.purchaseWith(
            PurchaseParams.Builder(this, pkg).build(),
            onError = { error, userCancelled -> /* ... */ },
            onSuccess = { _, customerInfo -> /* ... */ }
        )
    }
)
```

Configure your RevenueCat webhook to point at
`https://<api>/api/tracking/webhooks/revenuecat/<public_key>`, with the
Authorization header value set on your Site in the ROASSensor panel's "Connect
revenue" step. The purchase lands as revenue, attributed via the `appUserID`
(= our vid) or the `$idfa`/`$gpsAdId` subscriber attribute.

## How attribution works (the deterministic path)

```
1. Ad points at a ROASSensor smart link (/c/<slug>)
2. Android click → Play Store, with rsclid stamped into the `referrer`
3. Install → this SDK reads the referrer back → POST /mobile/first-open
4. Backend lifts rsclid + rs_* from the referrer, records the install
5. Purchase (RevenueCat) carries the vid → attributes to the exact ad
```

## Privacy

- Email/phone are SHA-256 hashed **on-device** (`Hashing`), byte-for-byte
  identical to the backend `security.py` and the web SDK `hash.ts` — verified by
  `HashingParityTest`. Raw PII never leaves the phone.
- The GAID is read only when Play Services is present and the user has **not**
  enabled "limit ad tracking" (opt-out is respected). Sent raw; the server hashes
  it. Add/keep the `com.google.android.gms.permission.AD_ID` permission to use it.
- Delivery is a persisted, idempotent queue: an install that happens offline is
  reported on the next launch, never lost or double-counted.

## Building & testing

From the repo root:

```bash
./gradlew :roas:testDebugUnitTest   # runs the JVM hash-parity tests
./gradlew :roas:assembleRelease     # builds the AAR
./gradlew :sample:assembleDebug     # builds the test app (tracking + RevenueCat)
```

Open the folder in Android Studio to develop. The hash-parity tests are pure JVM
(no device/emulator needed) and are the guard that keeps identity matching working.
`sample/` is the verified reference implementation for both the tracking calls
and the RevenueCat purchase flow — copy from it rather than the snippets above
if the two ever drift.

## Roadmap (this SDK)

- App-open / session events (currently first-open + events + identify).
- Deferred deep-link lookup (reuse the backend's click log + ip/ua matcher).
- Batching of high-frequency events.
- The iOS twin (`sdk-ios`, Swift) — same wire format, plus SKAdNetwork + Apple
  Search Ads attribution (see `docs/mobile-tracking-design.md §11.1`).
