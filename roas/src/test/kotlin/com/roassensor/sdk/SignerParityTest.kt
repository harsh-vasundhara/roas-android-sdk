package com.roassensor.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Byte-for-byte parity with the backend's `apps/tracking/signing.py` (and with
 * the iOS `SignerParityTests`, which pins the same vector).
 *
 * The expected value was produced by calling `signing.sign()` on the server, so
 * this fails the moment the Kotlin MAC drifts from the Python one. That drift is
 * the worst failure mode this feature has: every beacon from every Android build
 * would start returning 401, and only for customers who had switched enforcement
 * on — surfacing as one tenant's installs vanishing rather than as anything that
 * looks like a signing bug.
 *
 * Pure JVM (javax.crypto only), so it runs without a device or emulator.
 */
class SignerParityTest {

    private val secret = "s3cret-for-tests"
    private val body = """{"site":"abc","vid":"rs123"}""".toByteArray(Charsets.UTF_8)
    private val timestamp = 1_754_300_000L

    @Test
    fun `header matches the backend`() {
        assertEquals(
            "t=1754300000,v1=b33efdc904392639df4d8efd56ea603a802a2e24aa8a00a8ede94711d9dce2c1",
            Signer.header(secret, body, timestamp),
        )
    }

    @Test
    fun `the timestamp is inside the mac not beside it`() {
        // If it were merely sent alongside the digest, a captured beacon could be
        // replayed forever by rewriting `t`.
        assertNotEquals(
            Signer.header(secret, body, timestamp),
            Signer.header(secret, body, timestamp + 1),
        )
    }

    @Test
    fun `the body is covered`() {
        val tampered = """{"site":"abc","vid":"rs124"}""".toByteArray(Charsets.UTF_8)
        assertNotEquals(
            Signer.header(secret, body, timestamp),
            Signer.header(secret, tampered, timestamp),
        )
    }

    @Test
    fun `no secret means no header rather than an empty one`() {
        // An app that hasn't adopted signing must send NO header. An empty or
        // garbage one reads as INVALID server-side and is refused outright,
        // instead of as MISSING — which is what keeps old builds working.
        assertNull(Signer.header(null, body, timestamp))
        assertNull(Signer.header("", body, timestamp))
    }

    @Test
    fun `hex encoding is zero padded`() {
        // A naive Integer.toHexString drops the leading zero on bytes below 0x10,
        // producing a 63-character digest that the server can never match — and
        // only for roughly 1 in 16 inputs, so it would pass a casual smoke test.
        val digest = Signer.header(secret, body, timestamp)!!.substringAfter("v1=")
        assertEquals(64, digest.length)
    }
}
