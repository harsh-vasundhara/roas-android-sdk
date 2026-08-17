package com.roassensor.sdk

/**
 * The property keys ROASSensor understands in [Roas.track].
 *
 * ## Why a convention, when `properties` is a free-form map
 *
 * It stays free-form on purpose — an app should be able to record anything it
 * finds useful without waiting for an SDK release. But reporting can only
 * group by a key it can predict. One app sending `sku`, another `product_id`
 * and a third `item_id` produces three columns that mean the same thing and
 * join to nothing, and the mistake is invisible until someone tries to ask
 * "which product do people add and then not buy?" months of data later.
 *
 * So: use these keys where they fit, add your own freely alongside them.
 * Nothing here is enforced — an unrecognised key is stored and returned
 * exactly as sent — but a product funnel can only be built from [PRODUCT_ID].
 *
 * ## The one that actually matters
 *
 * [PRODUCT_ID] should be **the same identifier the purchase will arrive
 * with** — for RevenueCat that is the store product id, for Stripe the price
 * or product id. That is what lets an `add_to_cart` be lined up against the
 * purchase that did or did not follow it. A friendly name in [PRODUCT_NAME]
 * is for display only and should never be used as the join key: names get
 * edited, translated, and reused.
 *
 * ```kotlin
 * Roas.track(
 *     RoasEvent.ADD_TO_CART,
 *     properties = mapOf(
 *         RoasProps.PRODUCT_ID to "piano_course_annual",
 *         RoasProps.PRODUCT_NAME to "Annual Piano Course",
 *         RoasProps.QUANTITY to 1,
 *         RoasProps.PRICE to 4999,        // minor units, see below
 *         RoasProps.CURRENCY to "INR",
 *     ),
 * )
 * ```
 *
 * ## Money in an event is never revenue
 *
 * [PRICE] is reporting colour only. Anything a device claims about money is
 * unverifiable — the console is open to anyone — so no ROAS numerator reads
 * it; revenue enters solely through the signed webhook or the marketer's own
 * server. Send it for funnel context, never expecting it to appear in ROAS.
 */
object RoasProps {

    /** Store product id. **Must match what the purchase will report** (the
     *  RevenueCat/Play product id), or the funnel cannot line the two up. */
    const val PRODUCT_ID = "product_id"

    /** Display name. Never a join key — names change, ids do not. */
    const val PRODUCT_NAME = "product_name"

    /** Grouping for reports, e.g. "courses". */
    const val CATEGORY = "category"

    const val QUANTITY = "quantity"

    /** Minor units (paise/cents), so an integer stays exact. Reporting only —
     *  see the class doc: an event never contributes to revenue. */
    const val PRICE = "price"

    /** ISO-4217, e.g. "INR". */
    const val CURRENCY = "currency"

    /** Free-text, for [RoasEvent.SEARCH]. */
    const val QUERY = "query"

    /** Where in the app this happened ("home", "product_detail") — the same
     *  event from a carousel and a detail page are different behaviours. */
    const val SOURCE = "source"
}
