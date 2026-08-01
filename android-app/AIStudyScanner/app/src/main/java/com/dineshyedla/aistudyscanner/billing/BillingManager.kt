package com.aistudyscanner.agent.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the Play Billing connection and the Pro entitlement.
 *
 * One subscription product with two base plans, which is Google's current model —
 * it keeps the entitlement check to a single product id regardless of which plan
 * the user is on, and lets Play handle switching between them.
 *
 * Entitlement is cached in [ProPrefs] so a returning user is not gated on the
 * billing connection before they can use the app. Play remains the source of
 * truth: every successful [queryPurchases] overwrites the cache in both
 * directions, so a lapsed subscription loses access on the next connection.
 *
 * NOTE: this is a client-side check only. A determined user can defeat it. That
 * matches the existing posture of the daily quota, which is also client-enforced.
 * Server-side verification via the Play Developer API is the real fix and is not
 * built yet — see docs/SUBSCRIPTION_SETUP.md.
 */
object BillingManager {
    private const val TAG = "BillingManager"

    /** Must match the subscription product id created in Play Console. */
    const val PRODUCT_ID_PRO = "pro"

    /** Base plan ids inside that product. */
    const val BASE_PLAN_MONTHLY = "monthly"
    const val BASE_PLAN_YEARLY = "yearly"

    private var client: BillingClient? = null
    private var appContext: Context? = null

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _offers = MutableStateFlow<List<SubscriptionOffer>>(emptyList())
    val offers: StateFlow<List<SubscriptionOffer>> = _offers.asStateFlow()

    /** Null until a query completes; set so the paywall can explain itself. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                // Not an error worth surfacing — the user chose to back out.
                _lastError.value = null
            }
            else -> {
                _lastError.value = "Purchase failed (code ${result.responseCode})."
                Log.w(TAG, "Purchase update failed: ${result.debugMessage}")
            }
        }
    }

    /** Safe to call more than once; later calls are no-ops while connected. */
    fun start(context: Context) {
        appContext = context.applicationContext
        _isPro.value = ProPrefs.isPro(context)

        if (client?.isReady == true) {
            queryPurchases()
            return
        }

        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            .enablePendingPurchases()
            .build()
        client = c

        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    _lastError.value = "Google Play billing is unavailable on this device."
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnect lazily on the next start() rather than looping here.
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProductDetails() {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_PRO)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        c.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
                _lastError.value = "Couldn't load subscription prices."
                return@queryProductDetailsAsync
            }
            val details = list.firstOrNull { it.productId == PRODUCT_ID_PRO }
            if (details == null) {
                // Almost always means the product is missing or not yet active in
                // Play Console, which is silent otherwise and easy to misdiagnose.
                Log.w(TAG, "Product '$PRODUCT_ID_PRO' not found — is it active in Play Console?")
                _lastError.value = "Subscriptions aren't available yet. Please try again later."
                return@queryProductDetailsAsync
            }
            productDetails = details
            _offers.value = details.toOffers()
            _lastError.value = null
        }
    }

    /**
     * One entry per base plan. Free-trial and intro offers add extra entries for the
     * same base plan, so keep the cheapest first-phase price per plan — that is what
     * the user actually pays first, and what the paywall should show.
     */
    private fun ProductDetails.toOffers(): List<SubscriptionOffer> {
        val all = subscriptionOfferDetails ?: return emptyList()
        return all.mapNotNull { offer ->
            val firstPhase = offer.pricingPhases.pricingPhaseList.firstOrNull()
                ?: return@mapNotNull null
            SubscriptionOffer(
                basePlanId = offer.basePlanId,
                offerToken = offer.offerToken,
                formattedPrice = firstPhase.formattedPrice,
                priceMicros = firstPhase.priceAmountMicros,
                billingPeriod = firstPhase.billingPeriod,
            )
        }
            .groupBy { it.basePlanId }
            .map { (_, forPlan) -> forPlan.minBy { it.priceMicros } }
            .sortedBy { it.priceMicros }
    }

    fun queryPurchases() {
        val c = client ?: return
        if (!c.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            val entitled = purchases.any { it.grantsPro() }
            purchases.forEach { handlePurchase(it) }
            // Authoritative: also revokes when a subscription has lapsed.
            setPro(entitled)
        }
    }

    private fun Purchase.grantsPro(): Boolean =
        products.contains(PRODUCT_ID_PRO) && purchaseState == Purchase.PurchaseState.PURCHASED

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.grantsPro()) return

        setPro(true)

        // Acknowledge within three days or Google refunds it automatically. This is
        // the single most expensive thing to get wrong in a billing integration.
        if (purchase.isAcknowledged) return
        val c = client ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        c.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    private fun setPro(value: Boolean) {
        _isPro.value = value
        appContext?.let { ProPrefs.setPro(it, value) }
    }

    /** Opens Play's purchase sheet. Returns false if billing isn't ready yet. */
    fun launchPurchase(activity: Activity, offer: SubscriptionOffer): Boolean {
        val c = client ?: return false
        val details = productDetails ?: return false
        if (!c.isReady) return false

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offer.offerToken)
                        .build()
                )
            )
            .build()
        val result = c.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
            _lastError.value = "Couldn't open Google Play checkout."
            return false
        }
        return true
    }
}

/** A purchasable base plan, flattened for the paywall. */
data class SubscriptionOffer(
    val basePlanId: String,
    val offerToken: String,
    /** Localised and currency-correct, straight from Play — never hardcode a price. */
    val formattedPrice: String,
    val priceMicros: Long,
    /** ISO 8601 period, e.g. P1M or P1Y. */
    val billingPeriod: String,
) {
    val isYearly: Boolean get() = billingPeriod.contains("Y")

    val periodLabel: String
        get() = when {
            isYearly -> "per year"
            billingPeriod.contains("M") -> "per month"
            billingPeriod.contains("W") -> "per week"
            else -> billingPeriod
        }
}
