package com.procreate.android.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Talks to Google Play Billing: what the plans cost, whether this user has one, and starting a
 * purchase.
 *
 * Shape of the product, which the Play Console setup must match exactly: ONE subscription
 * ([PRODUCT_ID]) carrying TWO base plans ([BASE_PLAN_MONTHLY], [BASE_PLAN_YEARLY]). That is Play's
 * modern model - two separate subscription products would make switching between monthly and yearly
 * a cancel-and-resubscribe instead of the plan change Play handles natively.
 *
 * Entitlement is only ever granted from what Play reports, never from a local flag, and the check
 * runs on every launch so an expired or refunded subscription closes the gate again on its own.
 */
class BillingManager(context: Context) {

    companion object {
        /** Must match the subscription's product ID in Play Console exactly. */
        const val PRODUCT_ID = "artify_pro"
        const val BASE_PLAN_MONTHLY = "pro-monthly"
        const val BASE_PLAN_YEARLY = "pro-yearly"
    }

    /** A plan as Play actually priced it - never a price hardcoded in the app. Play localises the
     * currency and amount per country, so a hardcoded "٥٠ ريال" would be wrong for most users. */
    data class Plan(
        val basePlanId: String,
        val offerToken: String,
        val formattedPrice: String,
        val billingPeriod: String,
        val priceMicros: Long
    ) {
        val isYearly: Boolean get() = billingPeriod == "P1Y"
    }

    private val appContext = context.applicationContext

    @Volatile var plans: List<Plan> = emptyList()
        private set

    @Volatile private var productDetails: ProductDetails? = null
    @Volatile private var connected = false

    /** Set by the paywall so a purchase completed in Play's sheet can close it and refresh the UI. */
    var onPurchaseResult: ((success: Boolean, userCancelled: Boolean) -> Unit)? = null
    var onPlansLoaded: (() -> Unit)? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null -> {
                var granted = false
                for (purchase in purchases) {
                    if (handlePurchase(purchase)) granted = true
                }
                onPurchaseResult?.invoke(granted, false)
            }
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED ->
                onPurchaseResult?.invoke(false, true)
            else -> onPurchaseResult?.invoke(false, false)
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        // Without this, a dropped connection (Play updating in the background, for instance) leaves
        // the client dead and every later call silently failing.
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (connected) {
            refreshPurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    queryPlans()
                    refreshPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
            }
        })
    }

    private fun queryPlans() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val details = queryResult.productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync
            productDetails = details

            plans = details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
                // The last phase is the recurring one; earlier phases are free trials or intro
                // pricing. Showing an intro price as "the price" would misstate what the user is
                // signing up to pay from the second period onward.
                val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                Plan(
                    basePlanId = offer.basePlanId,
                    offerToken = offer.offerToken,
                    formattedPrice = phase.formattedPrice,
                    billingPeriod = phase.billingPeriod,
                    priceMicros = phase.priceAmountMicros
                )
            }.sortedBy { it.priceMicros }

            onPlansLoaded?.invoke()
        }
    }

    /**
     * Asks Play what this account actually owns and writes the answer through to [Entitlements].
     * Run on every launch: it is what restores a subscription on a new device, and equally what
     * revokes access after a refund, an expiry, or a cancellation.
     */
    fun refreshPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val active = purchases.any { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PRODUCT_ID)
            }
            purchases.forEach { handlePurchase(it) }
            Entitlements.setSubscribed(appContext, active)
        }
    }

    /** @return true when this purchase grants access. */
    private fun handlePurchase(purchase: Purchase): Boolean {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return false
        if (!purchase.products.contains(PRODUCT_ID)) return false

        // Play automatically refunds and revokes any purchase not acknowledged within three days,
        // so this is not optional bookkeeping - skipping it silently cancels real subscriptions.
        if (!purchase.isAcknowledged) {
            billingClient.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            ) { /* A failure here is retried by refreshPurchases on the next launch. */ }
        }

        Entitlements.setSubscribed(appContext, true)
        return true
    }

    /** Opens Play's purchase sheet for one plan. Must be called from the foreground activity. */
    fun launchPurchase(activity: Activity, plan: Plan): Boolean {
        val details = productDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(plan.offerToken)
                        .build()
                )
            )
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    fun release() {
        onPurchaseResult = null
        onPlansLoaded = null
        runCatching { billingClient.endConnection() }
    }
}
