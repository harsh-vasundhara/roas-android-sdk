package com.roassensor.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.models.StoreTransaction
import com.roassensor.sdk.Roas
import com.roassensor.sdk.RoasEvent
import com.roassensor.sdk.RoasLogLevel

/**
 * A bare test app for the ROASSensor Android SDK. No XML layout, no extra deps —
 * just buttons that call the SDK so you can watch the beacons hit your backend.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ FILL IN THE TWO VALUES BELOW before running.                             │
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

    // 3) RevenueCat's public Google API key for this project (Project settings → API keys).
    //    Not the same as the publicKey above — that one is ours, this one is RevenueCat's.
    private val revenueCatApiKey = "YOUR-REVENUECAT-PUBLIC-SDK-KEY"

    private lateinit var log: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Verbose on purpose in this sample: DEBUG is what surfaces the
        // referrer-resolution line (`RoasReferrer` tag) showing which channel
        // — Play, or a matching OEM store — actually answered on THIS device,
        // which is the thing worth watching when testing attribution on real
        // hardware. MUST be set before initialize(), since the install beacon
        // is reported from inside it.
        Roas.setLogLevel(RoasLogLevel.DEBUG)

        // This reports the install (first launch) and flushes any queued beacons.
        Roas.initialize(this, publicKey = publicKey, baseUrl = baseUrl)

        // appUserID = our vid, so the purchase RevenueCat's webhook reports later
        // carries the exact visitor whose ad click drove the install.
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(this, revenueCatApiKey)
                .appUserID(Roas.visitorId())
                .build()
        )

        root = LinearLayout(this).apply {
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

        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun append(line: String) = log.append("$line\n")

    // Pulls the current Offering's packages from RevenueCat and adds one "Buy"
    // button per package below the fetch button, so a real purchase is one tap
    // away once a product exists in Play Console + RevenueCat.
    private fun fetchOfferings() {
        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                val current = offerings.current
                if (current == null) {
                    append("→ offerings: none configured yet (check RevenueCat dashboard)")
                    return
                }
                append("→ offerings: '${current.identifier}' has ${current.availablePackages.size} package(s)")
                for (pkg in current.availablePackages) {
                    root.addView(button("Buy: ${pkg.identifier} (${pkg.product.price.formatted})") {
                        buyPackage(pkg)
                    })
                }
            }

            override fun onError(error: PurchasesError) {
                append("→ offerings error: ${error.message}")
            }
        })
    }

    private fun buyPackage(pkg: Package) {
        Purchases.sharedInstance.purchase(
            PurchaseParams.Builder(this, pkg).build(),
            object : PurchaseCallback {
                override fun onCompleted(purchase: StoreTransaction, customerInfo: CustomerInfo) {
                    append("→ purchase completed: ${purchase.productIds} (vid=${Roas.visitorId()})")
                }

                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    append(if (userCancelled) "→ purchase cancelled" else "→ purchase error: ${error.message}")
                }
            }
        )
    }
}
