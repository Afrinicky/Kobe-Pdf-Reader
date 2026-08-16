package com.kobe.reader.feature.paywall

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.monetization.PremiumManager
import com.kobe.reader.monetization.billing.BillingManager
import com.kobe.reader.monetization.billing.BillingState
import com.kobe.reader.monetization.billing.ProProduct
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val billing: BillingManager,
    premium: PremiumManager,
) : ViewModel() {

    val uiState: StateFlow<PaywallUiState> = combine(
        billing.state,
        billing.products,
        premium.isPro,
    ) { billingState, products, isPro ->
        PaywallUiState(billingState = billingState, products = products, isPro = isPro)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PaywallUiState(),
    )

    /** Connects to Play only when the paywall is actually shown. */
    fun start() = billing.start()

    fun purchase(activity: Activity, product: ProProduct) =
        billing.launchPurchase(activity, product)

    fun restore() {
        viewModelScope.launch { billing.refreshPurchases() }
    }
}

data class PaywallUiState(
    val billingState: BillingState = BillingState.Idle,
    val products: List<ProProduct> = emptyList(),
    val isPro: Boolean = false,
)
