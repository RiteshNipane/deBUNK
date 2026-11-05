package com.debunk.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.debunk.app.R
import com.debunk.app.data.DebunkRuntime
import com.debunk.app.service.DebunkAccessibilityService
import com.debunk.app.service.OverlayService

/**
 * Consent + permission onboarding and the control panel.
 *
 * Order matters and is enforced: 1) consent/disclosure, 2) overlay permission, 3) accessibility,
 * 4) notifications, 5) start. Google Play requires the accessibility disclosure to appear in-app,
 * before the user is sent to system settings, and to describe exactly what is read and why.
 */
class MainActivity : ComponentActivity() {

    /** Bumped in onResume so permission status re-reads after returning from system settings. */
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DebunkTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { HomeScreen(refreshTick) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }
}

@Composable
private fun HomeScreen(tick: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = DebunkRuntime.prefs

    var consent by remember { mutableStateOf(prefs.consentGiven) }
    val overlayOk = tick >= 0 && Settings.canDrawOverlays(ctx)
    val a11yOk = tick >= 0 && isAccessibilityEnabled(ctx)
    var notifOk by remember(tick) { mutableStateOf(notificationsGranted(ctx)) }
    val running by DebunkRuntime.overlayRunning.collectAsState()
    val paused by prefs.paused.collectAsState()
    val quota by DebunkRuntime.quota.collectAsState()
    androidx.compose.runtime.LaunchedEffect(tick) { DebunkRuntime.refreshQuota() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val needsNotif = Build.VERSION.SDK_INT >= 33
    val allReady = consent && overlayOk && a11yOk && (!needsNotif || notifOk)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(
                painter = painterResource(R.drawable.debunk_logo), contentDescription = "deBUNK logo",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
            )
            Text("deBUNK", style = MaterialTheme.typography.titleMedium)
        }

        // Hero: says what the app does in one sentence, and shows the current state.
        Text(
            when {
                running && paused -> "Paused. Your dot is waiting."
                running -> "Drop the dot on any line to check it."
                allReady -> "Ready. Start deBUNK to get your dot."
                else -> "Check any claim you read, with one tap."
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Drag the floating dot onto a headline or statement in any app. deBUNK reads that one line and compares it " +
                "with independent news sources and fact-checkers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (running) RunningPanel(paused, ctx) else SetupChecklist(
            consent = consent, onConsent = { consent = it; prefs.consentGiven = it },
            overlayOk = overlayOk, a11yOk = a11yOk, notifOk = notifOk, needsNotif = needsNotif,
            allReady = allReady,
            onNotif = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ctx = ctx,
        )

        quota?.let { UsageRow(it, onUpgrade = { ctx.startActivity(Intent(ctx, PremiumActivity::class.java)) }) }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        PrivacyNote()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UsageRow(q: com.debunk.app.data.QuotaInfo, onUpgrade: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (q.premium) "Premium" else "${q.left} of ${q.limit} free checks left today", style = MaterialTheme.typography.titleMedium)
            Text(
                if (q.premium) "Unlimited checks are on." else "Free checks reset every day.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!q.premium) {
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onUpgrade) { Text("Go premium") }
        }
    }
}

@Composable
private fun RunningPanel(paused: Boolean, ctx: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pause checking", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paused) "The dot is hidden from view until you resume." else "Turn on to hide the dot without stopping deBUNK.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = paused, onCheckedChange = { DebunkRuntime.setPaused(it) })
        }
        OutlinedButton(
            onClick = { ctx.startService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Stop deBUNK") }
        Text(
            "Tip: tap the dot for pause and ignore-this-app options. Drop it on a line and wait a moment for the verdict.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One checklist. Finished steps collapse to a single line; the first unfinished step is open with its button. */
@Composable
private fun SetupChecklist(
    consent: Boolean, onConsent: (Boolean) -> Unit,
    overlayOk: Boolean, a11yOk: Boolean, notifOk: Boolean, needsNotif: Boolean,
    allReady: Boolean, onNotif: () -> Unit, ctx: Context,
) {
    val current = when {
        !consent -> 1
        !overlayOk -> 2
        !a11yOk -> 3
        needsNotif && !notifOk -> 4
        else -> 5
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Step(1, "Review what deBUNK reads", consent, current == 1) {
            Text(
                "deBUNK uses an Accessibility service. It reads only the single line under the dot, only when you drop " +
                    "the dot on it. It does not scan your screen in the background.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "That line, including a chat message if you point at one, is sent to the deBUNK server, which uses an AI service to pull out the claim and look it up " +
                    "with news sources and fact-checkers. No screenshots, no passwords, nothing stored from your screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!DebunkRuntime.usesBackend) {
                Text("This build is offline: verdicts are simulated and nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth().clickable { onConsent(!consent) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = consent, onCheckedChange = onConsent)
                Text("I understand and agree", fontWeight = FontWeight.Medium)
            }
        }
        Step(2, "Allow display over other apps", overlayOk, current == 2) {
            Text("Lets deBUNK draw the dot and verdict box above the app you're reading. Touches still reach that app.", style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("Open overlay settings") {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }
        Step(3, "Turn on the accessibility service", a11yOk, current == 3) {
            Text(
                "In Accessibility settings, open deBUNK, switch it on and confirm. Android warns that the service can read " +
                    "screen content; that is the single line described in step 1.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryButton("Open accessibility settings") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (needsNotif) {
            Step(4, "Allow the status notification", notifOk, current == 4) {
                Text("Android needs a small notification to keep deBUNK running. It has Pause and Stop buttons.", style = MaterialTheme.typography.bodyMedium)
                PrimaryButton("Allow notifications", onClick = onNotif)
            }
        }
        Step(5, "Start deBUNK", done = false, open = current == 5) {
            Text("Your dot appears on screen. Drag it onto a line and let go.", style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("Start deBUNK", enabled = allReady) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, done: Boolean, open: Boolean, content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth()) {
        // Rail: a numbered marker (this content is a real sequence) joined by a thin line.
        Column(Modifier.width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(28.dp).clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else if (open) MaterialTheme.colorScheme.onSurface else line),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (done) "\u2713" else "$number", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (done) MaterialTheme.colorScheme.onPrimary
                    else if (open) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (number < 5) Box(Modifier.width(2.dp).height(if (open) 24.dp else 14.dp).background(line))
        }
        Column(Modifier.padding(start = 12.dp, bottom = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                color = if (open || done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (open) content()
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
    ) { Text(label) }
}

@Composable
private fun PrivacyNote() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What stays private", style = MaterialTheme.typography.titleMedium)
        Text(
            "Password fields and banking and payment apps are never read. In chat apps like WhatsApp, only the one message you drop the dot on is sent for checking. You can pause deBUNK or ignore an app " +
                "from the dot's menu at any time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun notificationsGranted(ctx: Context) =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** True if our AccessibilityService is switched on in system settings (short or long component names both match). */
private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, DebunkAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}
