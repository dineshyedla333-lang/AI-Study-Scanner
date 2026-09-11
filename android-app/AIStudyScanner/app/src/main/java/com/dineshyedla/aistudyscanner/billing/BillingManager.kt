package com.aistudyscanner.agent.billing

import android.app.Activity
import android.content.Context
import android.util.Log
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
 * [queryPurchases] also runs on every `onResume`, so a subscription bought or
 * cancelled in the Play Store app is reflected without restarting.
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

    /**
     * True while Play has accepted a Pro order but has not yet secured the payment.
     * Nothing is granted in this state. It exists so the paywall can say
     * "processing" instead of looking as though the purchase silently failed.
     */
    private val _isPaymentPending = MutableStateFlow(false)
    val isPaymentPending: StateFlow<Boolean> = _isPaymentPending.asStateFlow()

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

    /** Safe to call more than once; later calls only re-query. */
    fun start(context: Context) {
        appContext = context.applicationContext
        _isPro.value = ProPrefs.isPro(context)

        if (client != null) {
            queryPurchases()
            return
        }

        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            // PBL 8 removed the no-arg overload; enableOneTimeProducts() is its exact
            // equivalent. Play only supports pending payment on one-time products and
            // prepaid plans, and both Pro plans are auto-renewing, so for `pro` this is
            // required boilerplate rather than an opt-in.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            // After a drop, the library reconnects inside the next API call. Without
            // this a lost connection meant no billing until the process restarted,
            // because start() only runs from Application.onCreate.
            .enableAutoServiceReconnection()
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
                // enableAutoServiceReconnection() handles this on the next call;
                // nothing to retry here.
                Log.w(TAG, "Billing service disconnected; will reconnect on next call")
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

        c.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
                _lastError.value = "Couldn't load subscription prices."
                return@queryProductDetailsAsync
            }
            val details = queryResult.productDetailsList
                .firstOrNull { it.productId == PRODUCT_ID_PRO }
            if (details == null) {
                // PBL 8+ says why a product came back empty instead of dropping it
                // silently. Almost always: not created, or not yet active, in Play
                // Console — which is easy to misdiagnose without this.
                val why = queryResult.unfetchedProductList
                    .joinToString { "${it.productId}: status ${it.statusCode}" }
                    .ifEmpty { "not in response" }
                Log.w(TAG, "Product '$PRODUCT_ID_PRO' unavailable ($why) — is it active in Play Console?")
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

    /**
     * Refreshes entitlement from Play. Called after setup and from `onResume`.
     *
     * Deliberately no `isReady` guard: auto reconnection re-establishes a dropped
     * connection inside this call, and bailing early would prevent exactly that.
     * Before the first connection completes it answers SERVICE_DISCONNECTED, which is
     * harmless — onBillingSetupFinished queries again.
     */
    fun queryPurchases() {
        val c = client ?: return
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
            // Authoritative in both directions: revokes when a subscription has
            // lapsed, and clears "pending" once Play has cancelled or expired an
            // order that never completed — it simply stops appearing here.
            setPro(entitled)
            _isPaymentPending.value = !entitled && purchases.any { it.isPendingPro() }
        }
    }

    private fun Purchase.isPro(): Boolean = products.contains(PRODUCT_ID_PRO)

    private fun Purchase.grantsPro(): Boolean =
        isPro() && purchaseState == Purchase.PurchaseState.PURCHASED

    private fun Purchase.isPendingPro(): Boolean =
        isPro() && purchaseState == Purchase.PurchaseState.PENDING

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.isPendingPro()) {
            // Play accepted the order but the money is not secured yet. Never grant
            // here; a later purchase update or queryPurchases() flips it to PURCHASED.
            _isPaymentPending.value = true
            return
        }
        if (!purchase.grantsPro()) return

        _isPaymentPending.value = false
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
        // A non-null productDetails means we have connected at least once; if the
        // connection dropped since, auto reconnection restores it inside this call.
        val details = productDetails ?: return false

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
