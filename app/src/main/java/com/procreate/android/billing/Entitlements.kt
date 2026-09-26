package com.procreate.android.billing

import android.content.Context

/**
 * The single place that answers "is this user a subscriber?", and the single list of what a
 * subscription actually unlocks.
 *
 * Everything else in the app asks [isUnlocked] rather than testing a boolean of its own. That
 * matters more than it looks: a paywall spread across call sites drifts, and the failure is
 * asymmetric - a gate that wrongly opens costs revenue quietly, while a gate that wrongly closes
 * makes a paying user feel cheated. One function, one answer.
 *
 * The answer is cached on disk because Play is not always reachable. A subscriber on a plane must
 * keep their features, so the cache is trusted until Play contradicts it - [BillingManager]
 * refreshes it on every launch and after every purchase. The cache is a convenience, never the
 * source of truth: Play is.
 */
object Entitlements {

    /**
     * What a subscription unlocks. Drawing itself - the canvas, all 245 brushes, layers, colour,
     * PNG export - is deliberately absent: those are the reason someone installs the app at all,
     * and putting them behind a price would cost more in uninstalls than it could earn.
     */
    enum class Feature {
        /**
         * Reading a site plan with the vision model. **Currently free** - see [isUnlocked].
         */
        PLAN_ANALYSIS,

        /** Exporting Urban CAD work as DXF for AutoCAD and friends. */
        DXF_EXPORT
    }

    private const val PREFS = "artify_billing"
    private const val KEY_SUBSCRIBED = "is_subscribed"

    // Read on the UI thread on every gated action, so it is held in memory rather than hitting
    // disk each time; BillingManager writes through both.
    @Volatile private var cachedSubscribed: Boolean? = null

    fun init(context: Context) {
        cachedSubscribed = prefs(context).getBoolean(KEY_SUBSCRIBED, false)
    }

    /** True while the user has an active subscription, as far as this device last knew. */
    fun isSubscribed(context: Context): Boolean =
        cachedSubscribed ?: prefs(context).getBoolean(KEY_SUBSCRIBED, false)
            .also { cachedSubscribed = it }

    /**
     * Every gated feature routes through here. [Feature] exists so a future change of plan - moving
     * something in or out of the subscription - is an edit to this file, not a hunt through the app.
     */
    fun isUnlocked(context: Context, feature: Feature): Boolean = when (feature) {
        // Free, and deliberately so. In a Play build the app ships no API key, so plan analysis
        // runs on a Gemini key the user obtained and pays Google for themselves. Charging a
        // subscription for a feature the subscriber powers and funds is the kind of thing that
        // earns refund requests and one-star reviews, and it would deserve them.
        //
        // The check at the call site is kept rather than deleted: it is the seam that makes this
        // reversible. If the app ever proxies the model through a server on the developer's own
        // key - where a subscription would actually be paying for something - this line flips back
        // to isSubscribed(context) and the paywall reappears in the right place, with no hunting.
        Feature.PLAN_ANALYSIS -> true

        // Runs entirely on the device, costs nothing per use, and is what a working architect or
        // planner actually needs to get the drawing into AutoCAD. It carries the subscription.
        Feature.DXF_EXPORT -> isSubscribed(context)
    }

    /** Called only by [BillingManager], from what Play reports. Nothing else may grant access. */
    internal fun setSubscribed(context: Context, subscribed: Boolean) {
        cachedSubscribed = subscribed
        prefs(context).edit().putBoolean(KEY_SUBSCRIBED, subscribed).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
