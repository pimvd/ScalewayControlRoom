package com.pvd.scwcontrolroom.model

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pvd.scwcontrolroom.kit.ApiKey
import com.pvd.scwcontrolroom.kit.ProProducts

/** One way to pay for Pro: a base plan of the Pro subscription. */
data class ProPlan(
    val basePlan: String,
    val title: String,
    /** "€19.99 per year". */
    val price: String,
    /** Google Play's offer token; empty in the test store. */
    val offerToken: String = "",
)

/**
 * Pro: an auto-renewing subscription, checked on the device. There is no
 * server. [isPro] is the one gate.
 */
interface ProStore {
    val subscribed: Boolean
    val plans: List<ProPlan>
    /** The local test store: nothing is charged. */
    val isTestStore: Boolean

    suspend fun check()
    suspend fun loadPlans()
    suspend fun buy(activity: Activity, plan: ProPlan)
    suspend fun restore()
    fun manage(context: Context)

    /**
     * Pro features are open when subscribed, and while the demo is the only
     * key, so reviewers and the screenshots see everything.
     */
    fun isPro(keys: List<ApiKey>): Boolean = subscribed || (keys.isNotEmpty() && keys.all { it.isDemo })
}

/**
 * Sells Pro without Google Play, which needs the app to be listed there
 * first: a purchase only sets a flag on this device. Its plans and prices are
 * PlayStore/subscriptions.json's. Like Xcode's StoreKit test store for the
 * iPhone app.
 */
class MockProStore(context: Context) : ProStore {
    private val prefs = context.getSharedPreferences("test-store", Context.MODE_PRIVATE)

    override var subscribed by mutableStateOf(prefs.getBoolean("subscribed", false))
        private set
    override val isTestStore = true
    override val plans = listOf(
        ProPlan(ProProducts.YEARLY, "Pro, yearly", "€19.99 per year"),
        ProPlan(ProProducts.MONTHLY, "Pro, monthly", "€1.99 per month"),
    )

    override suspend fun check() {}
    override suspend fun loadPlans() {}
    override suspend fun buy(activity: Activity, plan: ProPlan) = set(true)
    override suspend fun restore() {}

    /** Cancels the test subscription, so the free app can be tried again. */
    override fun manage(context: Context) = set(false)

    private fun set(value: Boolean) {
        prefs.edit().putBoolean("subscribed", value).apply()
        subscribed = value
    }
}

/**
 * The APK on GitHub Releases cannot use Google Play Billing, so every Pro
 * feature is free there.
 */
object FreeProStore : ProStore {
    override val subscribed = true
    override val plans = emptyList<ProPlan>()
    override val isTestStore = false

    override suspend fun check() {}
    override suspend fun loadPlans() {}
    override suspend fun buy(activity: Activity, plan: ProPlan) {}
    override suspend fun restore() {}
    override fun manage(context: Context) {}
}
