package com.roassensor.sdk

/** How much the SDK writes to Logcat. Defaults to [ERROR] — enough to see a
 *  device that can never deliver anything, without spamming every successful
 *  beacon in a release build. */
enum class RoasLogLevel {
    NONE,
    ERROR,
    DEBUG,
}
