package com.roassensor.sdk

/**
 * The result shape shared by every OEM-native referrer reader (Vivo, Huawei,
 * and — via their reflective bridges — Xiaomi, Samsung). Deliberately
 * separate from [InstallReferrerReader.Referrer]/[InstallReferrerReader.Result]:
 * those types' doc comments are Play-specific ("Play bakes the referrer at
 * install time…"), and reusing them verbatim for a Vivo or Huawei read would
 * either mislead or need rewriting anyway. A second small pair of types costs
 * nothing and keeps each source's own semantics honest.
 */
internal object OemReferrer {

    /** [clickTimestampSeconds]/[installTimestampSeconds] mirror
     *  [InstallReferrerReader.Referrer]'s split for the same reason: the raw
     *  endpoints survive re-windowing later, a pre-computed gap doesn't. */
    data class Referrer(
        val referrer: String,
        val clickTimestampSeconds: Long?,
        val installTimestampSeconds: Long?,
    )

    /** [status]: "OK" (real referrer), "OK_EMPTY" (provider answered with
     *  nothing), "NOT_AVAILABLE" (provider doesn't exist on this device —
     *  the overwhelmingly common case for the three OEMs that aren't this
     *  one), or "EXCEPTION:<name>". No FEATURE_NOT_SUPPORTED/SERVICE_*
     *  vocabulary here — these are synchronous ContentProvider reads, not an
     *  async service with its own connection lifecycle. */
    data class Result(val referrer: Referrer?, val status: String)
}
