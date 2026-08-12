# ROASSensor Android SDK (`roas-android`)

Native Android tracking for ROASSensor. Reports app **installs** with deterministic
attribution (Google Play Install Referrer), **funnel events**, and **identity** —
and hands you a visitor id to thread into RevenueCat so **purchases** attribute to
the exact ad that drove the install.

It talks to the backend's `/api/tracking/mobile/*` endpoints (built in P0). Revenue
is **not** sent from the app — it enters only through the signed RevenueCat webhook,
so ROAS stays defensible.

## Install

Published as an AAR on Maven Central (as of 2026-07-28), module `:roas`:

```kotlin
dependencies {
    implementation("com.roassensor:roas:0.1.2")
}
```

> 0.1.1 is the newest version on Maven Central (since 2026-08-03). **0.1.2 is
> not published yet** — until it is, resolve it from `mavenLocal()` after
> running `./gradlew :roas:publishToMavenLocal` here.

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
- The device context (model, manufacturer, OS/API level, Play Store version,
  form factor, installer, locale, timezone, screen size) is **coarse and
  non-identifying** — every value is shared by millions of devices, and it is
  deliberately not a fingerprint: nothing here probes canvas, WebGL, audio, or
  sensors, and no serial, IMEI, `ANDROID_ID`, or MAC is read. It exists to
  answer "which device models fail to attribute", not to recognise a person.
  The GAID above remains the only device identifier, and it stays opt-out-able.

## Building & testing

```bash
cd sdk-android
./gradlew :roas:testDebugUnitTest   # runs the JVM hash-parity tests
./gradlew :roas:assembleRelease     # builds the AAR
```

Open the folder in Android Studio to develop. The hash-parity tests are pure JVM
(no device/emulator needed) and are the guard that keeps identity matching working.

## Diagnosing an install that didn't attribute

Every install reports **why** it did or didn't get a referrer, and **what
device** it was on. That combination is the whole point: the Play catalog is
~18,600 Android models, a QA rack is eight, and the failures cluster on the
models a small rack is least likely to hold — the Galaxy M32 and Tab S6 Lite
referrer failures had to be found by borrowing handsets one at a time.

`referrer_status` values, in order of what they mean:

| Status | Meaning | Retried? |
| --- | --- | --- |
| `OK` | A real referrer — our `rsclid` or a campaign name came through. | — |
| `OK_ORGANIC` | Play's `utm_medium=organic`. Nobody clicked an ad; this is a true answer, **not** a broken device. | — |
| `OK_NOT_SET` | Play returned `(not set)` — it had nothing to give, so the referrer was dropped between click and install. **This is the Galaxy M32 / Tab S6 Lite failure.** | No — Play bakes the referrer at install time. |
| `OK_EMPTY` | Read succeeded, referrer was blank. | No |
| `FEATURE_NOT_SUPPORTED` | That device's **Play Store app** is older than the Install Referrer API. Disproportionately tablets and rarely-updated budget/enterprise phones. | No |
| `SERVICE_UNAVAILABLE` / `SERVICE_DISCONNECTED` / `EXCEPTION:*` | Play Services wasn't ready. Most likely on the first cold launch after a store install, more likely still on a slow tablet. | **Yes**, up to 5 launches |
| `RETRY_OK…` | A retry recovered the referrer, sent as an `app_open`. Attributes identically; the prefix records that the first read failed. | — |
| `DEVELOPER_ERROR` / `PERMISSION_ERROR` | Our bug or a blocked permission. | No |

Alongside it every install carries `device_model` (`Build.MODEL`, e.g.
`SM-P613` — the key a Play device-catalog export joins on),
`device_manufacturer`, `os_version`, `api_level`, `device_type`,
`installer_package`, `sdk_version`, and — most usefully — `store_version`.

**`store_version` is the one that matters.** The Install Referrer API is gated
on the **Play Store app's** version (roughly 8.3.73+), *not* the Android
release. That is why a stale tablet fails while a phone on the same Android
version succeeds, and why `os_version` alone will mislead you.

```python
# Which devices are we losing, and why?
from django.db.models import Count
from apps.tracking.models import TouchPoint
(TouchPoint.objects
  .filter(event_type=TouchPoint.EventType.INSTALL, site__public_key="<key>")
  .values("device_manufacturer", "device_model", "device_type",
          "os_version", "store_version", "referrer_status")
  .annotate(n=Count("id")).order_by("-n"))
```

`OK*` over total is your real Android attribution coverage — per device, in
production, instead of per handset you can physically borrow.

## Roadmap (this SDK)

- App-open / session events (currently first-open + events + identify).
- Deferred deep-link lookup (reuse the backend's click log + ip/ua matcher).
- Batching of high-frequency events.
- The iOS twin (`sdk-ios`, Swift) — same wire format, plus SKAdNetwork + Apple
  Search Ads attribution (see `docs/mobile-tracking-design.md §11.1`).
