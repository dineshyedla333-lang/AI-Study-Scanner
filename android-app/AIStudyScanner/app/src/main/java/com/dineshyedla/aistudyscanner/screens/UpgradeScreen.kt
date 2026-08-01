package com.aistudyscanner.agent.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aistudyscanner.agent.billing.BillingManager
import com.aistudyscanner.agent.billing.SubscriptionOffer

private val ProAccent = Color(0xFF00838F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val isPro by BillingManager.isPro.collectAsState()
    val offers by BillingManager.offers.collectAsState()
    val error by BillingManager.lastError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Go Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isPro) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ProAccent.copy(alpha = 0.12f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "You're Pro",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ProAccent,
                        )
                        Text(
                            "No ads, and no daily limit on scans, home work, planners or " +
                                "current affairs.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // A real link, not just instructions. Play expects cancelling to be
                // easy to find, and a subscriber who cannot find it asks Google for a
                // refund and leaves a one-star review instead.
                OutlinedButton(
                    onClick = {
                        val url = "https://play.google.com/store/account/subscriptions" +
                            "?sku=${BillingManager.PRODUCT_ID_PRO}" +
                            "&package=${context.packageName}"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Manage or cancel subscription")
                }
                Text(
                    "Cancelling stops future renewals. You keep Pro until the end of the " +
                        "period you have already paid for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                "No ads. No daily limit.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "The free plan gives you 10 solves a day, and an ad to watch when you " +
                    "run out. Pro removes both.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "No ads, ever",
                    "Unlimited scan and solve",
                    "Unlimited Home Work practice sets",
                    "Unlimited AI Study Planner programs",
                    "Unlimited UPSC current-affairs questions",
                ).forEach { benefit ->
                    Text(
                        "•  $benefit",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (offers.isEmpty()) {
                Text(
                    error ?: "Loading plans…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Yearly first when present — it is the better value and the plan we
                // would rather sell.
                val ordered = offers.sortedByDescending { it.isYearly }
                ordered.forEach { offer ->
                    PlanCard(
                        offer = offer,
                        yearlyEquivalent = ordered.firstOrNull { it.isYearly },
                        monthly = ordered.firstOrNull { !it.isYearly },
                        onSelect = {
                            (context as? Activity)?.let { BillingManager.launchPurchase(it, offer) }
                        },
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                "Payment is charged to your Google Play account and renews automatically " +
                    "until you cancel. Cancel any time in the Play Store under Payments " +
                    "and subscriptions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanCard(
    offer: SubscriptionOffer,
    yearlyEquivalent: SubscriptionOffer?,
    monthly: SubscriptionOffer?,
    onSelect: () -> Unit,
) {
    val highlight = offer.isYearly
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) ProAccent.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (offer.isYearly) "Yearly" else "Monthly",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (highlight) {
                    Text(
                        "BEST VALUE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ProAccent,
                    )
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    offer.formattedPrice,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    offer.periodLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Savings are computed from Play's own prices rather than hardcoded, so
            // they stay correct if the price changes or the currency differs.
            if (offer.isYearly && monthly != null && yearlyEquivalent != null) {
                val twelveMonths = monthly.priceMicros * 12
                if (twelveMonths > yearlyEquivalent.priceMicros) {
                    val pct = (100 - (yearlyEquivalent.priceMicros * 100 / twelveMonths)).toInt()
                    Text(
                        "Save about $pct% versus paying monthly",
                        style = MaterialTheme.typography.bodySmall,
                        color = ProAccent,
                        textDecoration = TextDecoration.None,
                    )
                }
            }

            if (highlight) {
                Button(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ProAccent,
                        contentColor = Color.White,
                    ),
                ) { Text("Subscribe") }
            } else {
                OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
                    Text("Subscribe")
                }
            }
        }
    }
}
