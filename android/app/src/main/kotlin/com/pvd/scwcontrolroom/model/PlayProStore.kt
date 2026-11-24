package com.pvd.scwcontrolroom.model

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.android.billingclient.api.ProductDetails
import com.pvd.scwcontrolroom.kit.ProProducts
import kotlin.coroutines.resume
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Pro through Google Play Billing, checked on the device (there is no
 * server). Not used until the app is on Google Play: see MockProStore and
 * -Pstore=play (app/build.gradle.kts).
 */
class PlayProStore(context: Context) : ProStore {
    override var subscribed by mutableStateOf(false)
        private set
    override var plans by mutableStateOf(emptyList<ProPlan>())
        private set
    override val isTestStore = false

    private val scope = MainScope()
    private var product: ProductDetails? = null

    // Purchases made here, and elsewhere (another device, a renewal).
    private val client = BillingClient.newBuilder(context)
        .setListener { _, _ -> scope.launch { check() } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch { check() }
    }

    private suspend fun connected(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    override suspend fun check() {
        if (!connected()) return
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val purchases = client.queryPurchasesAsync(params).purchasesList.filter {
            ProProducts.PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        // Google refunds a purchase that is not acknowledged within three days.
        for (purchase in purchases.filter { !it.isAcknowledged }) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
        }
        subscribed = purchases.isNotEmpty()
    }

    override suspend fun loadPlans() {
        if (!connected()) return
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(ProProducts.PRODUCT)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
        )).build()
        val details = client.queryProductDetails(params).productDetailsList.orEmpty().firstOrNull() ?: return
        product = details
        val offers = details.subscriptionOfferDetails.orEmpty()
        plans = ProProducts.basePlans.mapNotNull { basePlan ->
            // The base plan itself, or an offer on it (a free trial, say).
            val offer = offers.filter { it.basePlanId == basePlan }.minByOrNull { it.offerId != null } ?: return@mapNotNull null
            val price = offer.pricingPhases.pricingPhaseList.last().formattedPrice
            val title = if (basePlan == ProProducts.YEARLY) "Pro, yearly" else "Pro, monthly"
            val per = if (basePlan == ProProducts.YEARLY) "year" else "month"
            ProPlan(basePlan, title, "$price per $per", offer.offerToken)
        }
    }

    override suspend fun buy(activity: Activity, plan: ProPlan) {
        val details = product ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(plan.offerToken)
                .build(),
        )).build()
        client.launchBillingFlow(activity, params)
    }

    override suspend fun restore() = check()

    override fun manage(context: Context) {
        val uri = "https://play.google.com/store/account/subscriptions?sku=${ProProducts.PRODUCT}&package=${context.packageName}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
