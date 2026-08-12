package com.roassensor.sdk

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 beacon signing.
 *
 * A native app sends no `Origin` header, so the collector has no way to tell a
 * real install from a `curl` carrying the (public) site key. Signing raises that
 * from "read the key off a network trace" to "reverse-engineer the binary".
 *
 * Honest about the ceiling: the secret ships inside the APK and any embedded
 * secret is extractable. What it buys is that scripted abuse stops working, a
 * captured beacon can't be replayed later (the timestamp is inside the MAC), and
 * an extracted secret is *recoverable* — the customer rotates it and every build
 * carrying the old one stops verifying.
 *
 * Wire format mirrors the Stripe webhook scheme the backend already verifies:
 *
 *     X-Roas-Signature: t=<epoch seconds>,v1=<hex>
 *     signed payload   = "<t>." + raw body bytes
 */
internal object Signer {

    const val HEADER = "X-Roas-Signature"

    private const val ALGORITHM = "HmacSHA256"

    /**
     * The header value for [body] at [epochSeconds], or null when no secret was
     * configured (an app that hasn't adopted signing yet — the server treats a
     * missing signature as an old build and accepts it until the customer turns
     * enforcement on).
     */
    fun header(secret: String?, body: ByteArray, epochSeconds: Long): String? {
        if (secret.isNullOrEmpty()) return null
        return try {
            val mac = Mac.getInstance(ALGORITHM)
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
            mac.update("$epochSeconds.".toByteArray(Charsets.UTF_8))
            mac.update(body)
            "t=$epochSeconds,v1=${mac.doFinal().toHex()}"
        } catch (t: Throwable) {
            // A missing JCE provider is not worth losing an install over: an
            // unsigned beacon is still accepted unless the customer enforces.
            null
        }
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            if (value < 0x10) out.append('0')
            out.append(Integer.toHexString(value))
        }
        return out.toString()
    }
}
