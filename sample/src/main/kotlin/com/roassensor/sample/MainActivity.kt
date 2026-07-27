package com.roassensor.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.roassensor.sdk.Roas
import com.roassensor.sdk.RoasEvent

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

    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This reports the install (first launch) and flushes any queued beacons.
        Roas.initialize(this, publicKey = publicKey, baseUrl = baseUrl)

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

        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun append(line: String) = log.append("$line\n")
}
