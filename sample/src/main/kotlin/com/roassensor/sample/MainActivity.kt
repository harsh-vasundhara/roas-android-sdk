package com.roassensor.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.getOfferingsWith
import com.revenuecat.purchases.models.StoreTransaction
import com.revenuecat.purchases.purchaseWith
import com.roassensor.sdk.Roas
import com.roassensor.sdk.RoasEvent

/**
 * A bare test app for the ROASSensor Android SDK. No XML layout, no extra deps —
 * just buttons that call the SDK so you can watch the beacons hit your backend.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ FILL IN THE THREE VALUES BELOW before running.                           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
class MainActivity : Activity() {

    // 1) Your backend, reachable FROM THE PHONE.
    //    • Physical phone on the same Wi-Fi as your PC → use your PC's LAN IP,
    //      e.g. "http://192.168.1.50:8000", and start Django with:
    //          python manage.py runserver 0.0.0.0:8000
    //      (find the IP with `ipconfig` → IPv4 Address; allow it through the firewall)
    //    • Android emulator → "http://10.0.2.2:8000" (that's the emulator's alias
    //      for your PC's localhost).
    private val baseUrl = "http://10.0.127.242:8000"
    // 2) An app property's public key (Site with platform=android) from the panel.
    private val publicKey = "360bd19f-b945-4c44-a410-8f9f14390cce"
    // 3) RevenueCat's own Google Play public API key (RevenueCat dashboard →
    //    Project settings → API keys — NOT the same thing as `publicKey` above,
    //    which is OUR public key). Leave blank to skip the RevenueCat buttons —
    //    the tracking buttons above still work with no RevenueCat key at all.
    private val revenueCatApiKey = "goog_YOUR_REVENUECAT_PUBLIC_SDK_KEY"

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This reports the install (first launch) and flushes any queued beacons.
        Roas.initialize(this, publicKey = publicKey, baseUrl = baseUrl)

        // Configure RevenueCat with OUR visitor id as its appUserID — this is the
        // one line that lets a RevenueCat purchase webhook attribute back to this
        // install (and its ad click) instead of arriving as an orphaned sale.
        // See Roas.kt's class-doc for the same snippet.
        if (revenueCatApiKey.isNotBlank() && !revenueCatApiKey.startsWith("goog_YOUR_")) {
            Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(
                PurchasesConfiguration.Builder(this, revenueCatApiKey)
                    .appUserID(Roas.visitorId())
                    .build()
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        log = TextView(this).apply {
            text = "ROASSensor SDK sample\nbaseUrl: $baseUrl\nvid: ${Roas.visitorId()}\n\nTap a button:\n"
            textSize = 13f
        }

        root.addView(button("Track: add_to_cart") {
            Roas.track(RoasEvent.ADD_TO_CART, properties = mapOf("sku" to "DEMO-1", "qty" to 1))
            append("→ track(add_to_cart) sent")
        })
        root.addView(button("Track: begin_checkout") {
            Roas.track(RoasEvent.BEGIN_CHECKOUT)
            append("→ track(begin_checkout) sent")
        })
        root.addView(button("Identify: buyer@example.com") {
            Roas.identify(email = "buyer@example.com")
            append("→ identify(email) sent")
        })
        root.addView(button("Show visitor id") {
            append("vid = ${Roas.visitorId()}")
        })
        root.addView(button("RevenueCat: fetch offerings") { fetchOfferings() })
        root.addView(button("RevenueCat: purchase first package") { purchaseFirstPackage() })

        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
    }

    /** Lists what's configured in RevenueCat's dashboard for this app — an empty
     *  result means no product/offering has been set up there yet, which is
     *  expected until Play Console in-app products exist and RevenueCat has
     *  synced them. */
    private fun fetchOfferings() {
        if (!Purchases.isConfigured) {
            append("RevenueCat not configured — set revenueCatApiKey above first.")
            return
        }
        Purchases.sharedInstance.getOfferingsWith(
            onError = { error: PurchasesError -> append("→ getOfferings failed: ${error.message}") },
            onSuccess = { offerings: Offerings ->
                val current = offerings.current
                if (current == null) {
                    append("→ no current offering (configure one in the RevenueCat dashboard)")
                } else {
                    append("→ offering '${current.identifier}': ${current.availablePackages.size} package(s)")
                }
            }
        )
    }

    /** Buys the first package of the current offering, purely to prove the
     *  purchase → webhook → our backend path end to end. Requires a real
     *  in-app product configured in Play Console and synced to RevenueCat —
     *  there is no way to fake this locally. */
    private fun purchaseFirstPackage() {
        if (!Purchases.isConfigured) {
            append("RevenueCat not configured — set revenueCatApiKey above first.")
            return
        }
        Purchases.sharedInstance.getOfferingsWith(
            onError = { error: PurchasesError -> append("→ getOfferings failed: ${error.message}") },
            onSuccess = { offerings: Offerings ->
                val pkg = offerings.current?.availablePackages?.firstOrNull()
                if (pkg == null) {
                    append("→ no purchasable package available")
                    return@getOfferingsWith
                }
                Purchases.sharedInstance.purchaseWith(
                    PurchaseParams.Builder(this, pkg).build(),
                    onError = { error: PurchasesError, userCancelled: Boolean ->
                        append(
                            if (userCancelled) "→ purchase cancelled"
                            else "→ purchase failed: ${error.message}"
                        )
                    },
                    onSuccess = { _: StoreTransaction?, customerInfo: CustomerInfo ->
                        append("→ purchase completed, active entitlements: ${customerInfo.entitlements.active.keys}")
                    }
                )
            }
        )
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun append(line: String) = log.append("$line\n")
}
