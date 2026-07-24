package com.roassensor.sdk

import java.security.MessageDigest
import java.text.Normalizer

/**
 * PII hashing, mirrored **BYTE-FOR-BYTE** from the backend `security.py`
 * (`hash_email` / `normalize_phone` / `hash_phone`) and the web SDK
 * `sdk/src/hash.ts`. Email/phone are hashed on-device so the raw value never
 * leaves the phone; the server matches on the hash.
 *
 * Break this parity and identity matching silently fails — a phone typed on a
 * non-Latin keypad would hash one way here and another on the server — so the
 * three implementations MUST stay identical. See docs/tracking-pipeline-guide.md.
 */
internal object Hashing {
    private const val MIN_PHONE_DIGITS = 7

    fun sha256Hex(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        // `b.toInt() and 0xFF` is NOT optional: a Kotlin Byte is signed, so any
        // byte >= 0x80 would sign-extend under "%02x" to "ffffff80" and corrupt
        // the hash — silently breaking parity with the backend for nearly every
        // digest. (A SHA-256 almost always contains a byte >= 0x80.)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    /** Unsalted SHA-256 of the trimmed, lowercased email (Meta CAPI form). */
    fun hashEmail(email: String?): String {
        val normalized = (email ?: "").trim().lowercase()
        return if (normalized.isEmpty()) "" else sha256Hex(normalized)
    }

    /**
     * Unsalted SHA-256 of the normalized phone (leading `+` kept). Returns "" for
     * fewer than 7 digits, so a garbled number never becomes a matchable key —
     * matching `hash_phone`'s guard exactly.
     */
    fun hashPhone(phone: String?): String {
        val normalized = normalizePhone(phone)
        val digits = normalized.replace("+", "")
        return if (digits.length < MIN_PHONE_DIGITS) "" else sha256Hex(normalized)
    }

    /**
     * Three identical steps to `normalize_phone` / `normalizePhone`:
     *   1. NFKC-normalize (folds full-width digits and other compatibility forms).
     *   2. Fold Arabic-Indic (U+0660–0669) and Extended/Persian (U+06F0–06F9)
     *      digits to ASCII (NFKC leaves these).
     *   3. Keep only ASCII digits and a leading `+`.
     */
    private fun normalizePhone(phone: String?): String {
        val nfkc = Normalizer.normalize(phone ?: "", Normalizer.Form.NFKC)
        val folded = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            when (cp) {
                in 0x0660..0x0669 -> folded.append(('0' + (cp - 0x0660)))
                in 0x06F0..0x06F9 -> folded.append(('0' + (cp - 0x06F0)))
                else -> folded.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return buildString {
            for (ch in folded) if (ch in '0'..'9' || ch == '+') append(ch)
        }
    }
}
