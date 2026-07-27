# ROASSensor Android SDK (`roas-android`)

Native Android tracking for ROASSensor. Reports app **installs** with deterministic
attribution (Google Play Install Referrer), **funnel events**, and **identity** —
and hands you a visitor id to thread into RevenueCat so **purchases** attribute to
the exact ad that drove the install.

It talks to the backend's `/api/tracking/mobile/*` endpoints (built in P0). Revenue
is **not** sent from the app — it enters only through the signed RevenueCat webhook,
so ROAS stays defensible.

## Install

Published as an AAR (module `:roas`). With a Maven/JitPack coordinate:

```kotlin
dependencies {
    implementation("com.roassensor:roas-android:0.1.0")
}
```

Requires `minSdk 21`. Play Services (`play-services-ads-identifier`) is optional —
without it the SDK still works, just referrer-only (no GAID).

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

// Attribute purchases: pass the vid to RevenueCat as the app user id
Purchases.configure(
    PurchasesConfiguration.Builder(this, "revenuecat_public_key")
        .appUserID(Roas.visitorId())
        .build()
)
```

Configure your RevenueCat webhook to point at
`https://<api>/api/tracking/webhooks/revenuecat/<public_key>` and the purchase
lands as revenue, attributed via the `appUserID` (= our vid) or the `$idfa`
subscriber attribute.

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

```bash
cd sdk-android
./gradlew :roas:testDebugUnitTest   # runs the JVM hash-parity tests
./gradlew :roas:assembleRelease     # builds the AAR
```

Open the folder in Android Studio to develop. The hash-parity tests are pure JVM
(no device/emulator needed) and are the guard that keeps identity matching working.

## Roadmap (this SDK)

- App-open / session events (currently first-open + events + identify).
- Deferred deep-link lookup (reuse the backend's click log + ip/ua matcher).
- Batching of high-frequency events.
- The iOS twin (`sdk-ios`, Swift) — same wire format, plus SKAdNetwork + Apple
  Search Ads attribution (see `docs/mobile-tracking-design.md §11.1`).
