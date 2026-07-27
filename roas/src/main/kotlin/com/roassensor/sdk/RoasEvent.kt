package com.roassensor.sdk

/**
 * Funnel / behaviour events — the app equivalent of `roas.track()`. These feed
 * the funnel and intent signals; they are **never** revenue (revenue enters only
 * through the signed RevenueCat/Stripe webhook, for the same reason the web SDK
 * can't book money: an app binary can be tampered with).
 *
 * The taxonomy covers commerce and game funnels (informed by common app events);
 * anything not listed can be sent with [CUSTOM] plus a name.
 */
enum class RoasEvent(val key: String) {
    VIEW_CONTENT("view_content"),
    ADD_TO_CART("add_to_cart"),
    ADD_TO_WISHLIST("add_to_wishlist"),
    BEGIN_CHECKOUT("begin_checkout"),
    SEARCH("search"),
    LEAD("lead"),
    SIGN_UP("sign_up"),
    LOGIN("login"),
    START_TRIAL("start_trial"),
    SUBSCRIBE("subscribe"),
    LEVEL_START("level_start"),
    LEVEL_COMPLETE("level_complete"),
    TUTORIAL_COMPLETE("tutorial_complete"),
    SHARE("share"),
    CUSTOM("custom"),
}
