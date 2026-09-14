package com.roassensor.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.ProductType
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
 * │ Configure it in sample/roas.properties (copy roas.properties.example).   │
 * │ Nothing environment-specific lives in this file.                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * What it exercises, so a test pass here proves the same paths the Flutter
 * and React Native samples prove:
 *   * install / session start on first launch (initialize)
 *   * SIGNED beacons, when roas.appSecret is set
 *   * deep link → app_open via handleDeepLink (roassample://open?rsclid=…)
 *   * events + identify
 *   * purchase verification via verifyPurchase — from a real RevenueCat
 *     purchase, or from a purchase token pasted in by hand
 * Every delivery outcome is echoed on screen through setOnDeliveryResult, so a
 * 401 (bad signature) or a 422 (Play didn't recognise the receipt) is visible
 * without adb.
 */
class MainActivity : Activity() {

    private val baseUrl = BuildConfig.ROAS_BASE_URL
    private val publicKey = BuildConfig.ROAS_PUBLIC_KEY
    private val appSecret = BuildConfig.ROAS_APP_SECRET.ifBlank { null }
    private val revenueCatApiKey = BuildConfig.REVENUECAT_API_KEY

    private lateinit var log: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        log = TextView(this).apply { textSize = 13f }

        if (publicKey.isBlank()) {
            log.text = "sample/roas.properties is missing or roas.publicKey is blank.\n" +
                "Copy roas.properties.example, fill it in, and rebuild."
            root.addView(log)
            setContentView(root)
            return
        }

        // Verbose on purpose in this sample: DEBUG is what surfaces the
        // referrer-resolution line (`RoasReferrer` tag) showing which channel
        // — Play, or a matching OEM store — actually answered on THIS device,
        // which is the thing worth watching when testing attribution on real
        // hardware. MUST be set before initialize(), since the install beacon
        // is reported from inside it.
        Roas.setLogLevel(RoasLogLevel.DEBUG)

        // Echo every delivery on screen. Registered before initialize() so the
        // install/session-start beacons it fires are reported too.
        Roas.setOnDeliveryResult { path, success, error ->
            runOnUiThread {
                append(if (success) "✓ $path" else "✗ $path — ${error ?: "failed"}")
            }
        }

        // This reports the install (first launch) and flushes any queued beacons.
        // appSecret is LAST and named, never positional — see the note on
        // Roas.initialize about the Flutter bridge.
        Roas.initialize(this, publicKey = publicKey, baseUrl = baseUrl, appSecret = appSecret)

        // An app opened THROUGH a link while already installed: forward the
        // link's campaign context. (A cold start from a link lands here; a warm
        // one lands in onNewIntent below.)
        intent?.dataString?.let { Roas.handleDeepLink(it) }

        if (revenueCatApiKey.isNotBlank()) {
            // appUserID = our vid, so the purchase RevenueCat's webhook reports
            // later carries the exact visitor whose ad click drove the install.
            Purchases.logLevel = LogLevel.DEBUG
            Purchases.configure(
                PurchasesConfiguration.Builder(this, revenueCatApiKey)
                    .appUserID(Roas.visitorId())
                    .build()
            )
        }

        log.append(
            "ROASSensor SDK sample\n" +
                "baseUrl: $baseUrl\n" +
                "signing: ${if (appSecret != null) "ON" else "OFF (roas.appSecret blank)"}\n" +
                "revenuecat: ${if (revenueCatApiKey.isNotBlank()) "configured" else "not configured"}\n" +
                "vid: ${Roas.visitorId()}\n\n" +
                "Tap a button:\n"
        )

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
        root.addView(button("Simulate deep link (rsclid=TEST123)") {
            // Same call the intent path makes; handy on a device with no shell.
            // For the REAL path use the adb command in AndroidManifest.xml.
            val url = "roassample://open?rsclid=TEST123&utm_source=meta&rs_campaign=summer_sale"
            Roas.handleDeepLink(url)
            append("→ handleDeepLink($url)")
        })
        root.addView(button("Verify purchase (paste Play token)") { promptVerifyPurchase() })
        if (revenueCatApiKey.isNotBlank()) {
            root.addView(button("RevenueCat: fetch offerings") { fetchOfferings() })
        }

        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.dataString?.let {
            append("→ deep link received: $it")
            Roas.handleDeepLink(it)
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun append(line: String) = log.append("$line\n")

    // A purchase token from anywhere — a Play test purchase in another build,
    // the Play Console's order page, a RevenueCat event — so the server-side
    // verification path can be exercised without wiring RevenueCat into this
    // sample at all.
    private fun promptVerifyPurchase() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val token = EditText(this).apply {
            hint = "purchase token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val product = EditText(this).apply { hint = "product id (SKU)" }
        val subscription = android.widget.CheckBox(this).apply { text = "subscription" }
        form.addView(token)
        form.addView(product)
        form.addView(subscription)
        AlertDialog.Builder(this)
            .setTitle("Verify purchase")
            .setView(form)
            .setPositiveButton("Verify") { _, _ ->
                val t = token.text.toString().trim()
                val p = product.text.toString().trim()
                Roas.verifyPurchase(t, p, subscription.isChecked)
                append("→ verifyPurchase(${t.take(12)}…, $p, sub=${subscription.isChecked}) sent")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
                    // Both routes to the same conversion: the RevenueCat webhook
                    // will report it too, and the server dedupes on the Play
                    // order id — so this is the FAST path, not a second sale.
                    val productId = purchase.productIds.firstOrNull() ?: return
                    Roas.verifyPurchase(
                        purchase.purchaseToken,
                        productId,
                        isSubscription = purchase.type == ProductType.SUBS,
                    )
                    append("→ verifyPurchase($productId) sent")
                }

                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    append(if (userCancelled) "→ purchase cancelled" else "→ purchase error: ${error.message}")
                }
            }
        )
    }
}
