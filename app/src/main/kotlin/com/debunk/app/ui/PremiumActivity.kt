package com.debunk.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.debunk.app.data.DebunkRuntime

/**
 * Premium screen: what the plan includes, how many free checks are left, and the upgrade button.
 *
 * The purchase itself is not connected yet. [startPurchase] is the single place to plug Google Play Billing in:
 * after a purchase is verified on the server, the server marks this device premium (POST /v1/admin/premium),
 * and the app picks it up through the quota refresh below.
 */
class PremiumActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DebunkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PremiumScreen(onBack = ::finish, onUpgrade = ::startPurchase)
                }
            }
        }
    }

    private fun startPurchase() {
        // TODO(billing): launch the Google Play Billing flow here (product id e.g. "premium_monthly").
        Toast.makeText(this, "Premium purchase isn't available yet. It will open Google Play checkout once billing is set up.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PremiumScreen(onBack: () -> Unit, onUpgrade: () -> Unit) {
    val ctx = LocalContext.current
    val quota by DebunkRuntime.quota.collectAsState()
    LaunchedEffect(Unit) { DebunkRuntime.refreshQuota() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            if (quota?.premium == true) "You have premium" else "Check as much as you want",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            when {
                quota?.premium == true -> "Unlimited checks are on for this phone."
                quota != null -> "Free plan: ${quota!!.left} of ${quota!!.limit} checks left today. They reset every day."
                else -> "Free plan: a limited number of checks each day, resetting daily."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Premium includes", style = MaterialTheme.typography.titleMedium)
            Text("Unlimited checks every day", style = MaterialTheme.typography.bodyMedium)
            Text("Works in every app, including chats", style = MaterialTheme.typography.bodyMedium)
            Text("Supports keeping the news sources and AI running", style = MaterialTheme.typography.bodyMedium)
        }
        if (quota?.premium != true) {
            Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
                Text("Upgrade to premium")
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Back")
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Support info", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your anonymous device id. Share it with support if a purchase doesn't show up.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer { Text(DebunkRuntime.deviceId(), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
