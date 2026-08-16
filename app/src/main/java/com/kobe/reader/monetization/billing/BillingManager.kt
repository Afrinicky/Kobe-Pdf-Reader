package com.kobe.reader.monetization.billing

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
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.kobe.reader.R
import com.kobe.reader.di.ApplicationScope
import com.kobe.reader.monetization.PremiumManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Play Billing, wrapped so the rest of the app never imports it.
 *
 * Design notes:
 *  - The connection is established lazily, the first time a paywall is shown.
 *    An offline-first PDF reader has no business opening a Play connection at
 *    launch, and doing so is a measurable cold-start cost.
 *  - Play is the source of truth. [PremiumManager] holds a local mirror so the
 *    app behaves correctly offline, and [refreshPurchases] reconciles it.
 *  - A purchase must be **acknowledged** within three days or Play refunds it
 *    automatically. That is the single most common way an indie app loses money,
 *    so acknowledgement happens immediately after the entitlement is granted.
 */
@Singleton
class BillingManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val premium: PremiumManager,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<BillingState>(BillingState.Idle)
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val _products = MutableStateFlow<List<ProProduct>>(emptyList())
    val products: StateFlow<List<ProProduct>> = _products.asStateFlow()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                scope.launch { purchases.orEmpty().forEach { handlePurchase(it) } }
                _state.value = BillingState.Idle
            }

            BillingClient.BillingResponseCode.USER_CANCELED ->
                _state.value = BillingState.Idle

            else -> {
                Log.w(TAG, "purchase failed: ${result.debugMessage}")
                _state.value = BillingState.Error(result.responseCode)
            }
        }
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(purchasesListener)
            // Required from Billing 6 onward; without it, pending (cash/voucher)
            // purchases are never delivered.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
    }

    /** Connects if needed, then loads products and reconciles existing purchases. */
    fun start() {
        if (client.isReady) {
            scope.launch { loadProducts(); refreshPurchases() }
            return
        }
        _state.value = BillingState.Connecting
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _state.value = BillingState.Idle
                    scope.launch { loadProducts(); refreshPurchases() }
                } else {
                    Log.w(TAG, "billing setup failed: ${result.debugMessage}")
                    _state.value = BillingState.Unavailable
                }
            }

            override fun onBillingServiceDisconnected() {
                // Play kills the connection routinely. Reconnect on next use
                // rather than looping here, which would spin on a device with
                // no Play Services at all.
                _state.value = BillingState.Idle
            }
        })
    }

    fun launchPurchase(activity: Activity, product: ProProduct) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product.details)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "could not launch billing flow: ${result.debugMessage}")
            _state.value = BillingState.Error(result.responseCode)
        }
    }

    /** "Restore purchase" - re-reads what Play says this account owns. */
    suspend fun refreshPurchases() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = client.queryPurchasesAsync(params)

        val owned = result.purchasesList.filter { it.isEntitling() }
        if (owned.isEmpty()) {
            // Deliberately does *not* revoke: an empty list also happens when
            // Play is unreachable, and taking Pro away from a paying user
            // because their train went into a tunnel is unacceptable.
            return
        }
        owned.forEach { handlePurchase(it) }
    }

    private suspend fun loadProducts() {
        val ids = listOf(
            context.getString(R.string.sku_pro_lifetime),
            context.getString(R.string.sku_pro_yearly),
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ids.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()

        val result = runCatching { client.queryProductDetails(params) }.getOrNull() ?: return
        _products.value = result.productDetailsList
            .orEmpty()
            .map { details ->
                ProProduct(
                    id = details.productId,
                    title = details.name,
                    description = details.description,
                    formattedPrice = details.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty(),
                    details = details,
                )
            }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (!purchase.isEntitling()) return

        premium.grantPro()

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            val result = client.acknowledgePurchase(params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "acknowledge failed: ${result.debugMessage}")
            }
        }
        _state.value = BillingState.Purchased
    }

    private fun Purchase.isEntitling(): Boolean =
        purchaseState == Purchase.PurchaseState.PURCHASED

    private companion object {
        const val TAG = "BillingManager"
    }
}

data class ProProduct(
    val id: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
    internal val details: ProductDetails,
)

sealed interface BillingState {
    data object Idle : BillingState
    data object Connecting : BillingState

    /** No Play Services, or the store rejected setup. Paywall shows a notice. */
    data object Unavailable : BillingState
    data object Purchased : BillingState
    data class Error(val responseCode: Int) : BillingState
}
