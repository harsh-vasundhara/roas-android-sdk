package com.roassensor.sdk

/**
 * The contract an optional OEM-referrer module (`:roas-xiaomi-referrer`,
 * `:roas-samsung-referrer`) reports back through. A plain Java-shaped
 * single-method interface — not a Kotlin lambda type — specifically so the
 * core `:roas` module's reflective caller ([XiaomiReferrerBridge],
 * [SamsungReferrerBridge]) can construct an instance of it directly at the
 * call site (`object : OemReferrerCallback { ... }`) without needing a
 * `java.lang.reflect.Proxy`. Only the *bridge class itself* — a small,
 * first-party, ROASSensor-designed method — is looked up via reflection;
 * this interface is a real compile-time type in every module involved, since
 * the optional modules depend on `:roas` (never the other way — that's what
 * keeps `:roas` free of a hard dependency on either vendor's own library).
 */
// Public (not internal, unlike the rest of this file's package): the two
// optional referrer modules are SEPARATE Gradle compilation units that
// depend on :roas, so they need real cross-module visibility into this one
// type to implement it — the only reason anything here isn't `internal`.
interface OemReferrerCallback {
    fun onResult(referrer: String?, clickTimestampSeconds: Long?, installTimestampSeconds: Long?, status: String)
}
