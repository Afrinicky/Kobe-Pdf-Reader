package com.kobe.reader.feature.paywall

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.monetization.ProFeature
import com.kobe.reader.monetization.billing.BillingState
import com.kobe.reader.monetization.billing.ProProduct

/**
 * The Pro upsell.
 *
 * Leads with what the user was just blocked from rather than a generic pitch,
 * and repeats the privacy promise: the most likely objection to paying for a
 * document app is "what happens to my files", and the honest answer is a
 * selling point.
 */
@Composable
fun PaywallScreen(
    triggeredBy: String?,
    onDismiss: () -> Unit,
    activityProvider: () -> Activity?,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(state.isPro) {
        if (state.isPro) onDismiss()
    }

    val blockedFeature = triggeredBy?.let { name ->
        runCatching { ProFeature.valueOf(name) }.getOrNull()
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            Icon(
                painter = painterResource(R.drawable.ic_pdf),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.pro_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))

            Text(
                text = blockedFeature
                    ?.let { stringResource(R.string.pro_locked_feature, stringResource(it.labelRes)) }
                    ?: stringResource(R.string.pro_headline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            BenefitList()

            Spacer(Modifier.height(28.dp))

            when {
                state.billingState is BillingState.Connecting -> CircularProgressIndicator()

                state.billingState is BillingState.Unavailable -> Text(
                    text = stringResource(R.string.pro_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                state.products.isEmpty() -> CircularProgressIndicator()

                else -> state.products.forEach { product ->
                    ProductButton(
                        product = product,
                        onClick = {
                            activityProvider()?.let { viewModel.purchase(it, product) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = viewModel::restore,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pro_restore)) }

            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pro_maybe_later)) }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BenefitList() {
    val benefits = listOf(
        R.string.pro_benefit_no_ads,
        R.string.pro_benefit_unlimited,
        R.string.pro_benefit_compression,
        R.string.pro_benefit_protect,
        R.string.pro_benefit_export,
        R.string.pro_benefit_batch,
        R.string.pro_benefit_privacy,
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            benefits.forEach { benefit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(benefit),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductButton(product: ProProduct, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(product.title, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(product.formattedPrice)
    }
}
