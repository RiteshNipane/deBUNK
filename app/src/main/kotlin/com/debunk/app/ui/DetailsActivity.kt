package com.debunk.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debunk.app.overlay.color
import com.debunk.core.domain.DotState
import com.debunk.core.domain.SourceRef
import com.debunk.core.domain.Stance
import com.debunk.core.domain.VerdictResult
import com.debunk.core.domain.toDotState

/**
 * In-process hand-off of the verdict being shown. It holds the claim + verdict only (never raw screen text)
 * and is cleared when the sheet closes.
 */
object DetailsHolder {
    @Volatile internal var current: VerdictResult? = null

    fun show(context: Context, result: VerdictResult) {
        current = result
        context.startActivity(Intent(context, DetailsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Bottom sheet over the app the user was reading: full claim, why, and every source with stance, date and link. */
class DetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = DetailsHolder.current
        if (result == null) { finish(); return }
        setContent { Sheet(result, onClose = ::finish, onOpen = ::openLink) }
    }

    override fun onDestroy() {
        if (isFinishing) DetailsHolder.current = null
        super.onDestroy()
    }

    private fun openLink(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" && uri.scheme != "http") return   // never launch arbitrary schemes from backend data
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun Sheet(result: VerdictResult, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFECEEFA) else Color(0xFF14172B)
    val paper = if (dark) Color(0xFF191C33) else Color(0xFFF7F8FC)
    val dot = result.verdict.toDotState()
    val spine = dot.color()

    // Scrim: tapping outside the sheet closes it.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(paper)
                .clickable(enabled = false) {}   // swallow taps so they don't reach the scrim
                .navigationBarsPadding().verticalScroll(rememberScrollState()),
        ) {
            // Verdict band: the one bold moment. Colour fills the top, word and number carry the meaning.
            Row(
                Modifier.fillMaxWidth().background(spine).padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onBand = if (dot == DotState.CAUTION) Color(0xFF14172B) else Color.White
                Column(Modifier.weight(1f)) {
                    Text(result.verdict.label, color = onBand, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (result.developing) Text("Developing story, may change", color = onBand.copy(alpha = 0.85f), fontSize = 13.sp)
                }
                Text("${result.confidence}%", color = onBand, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp)
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (result.degraded) {
                    Text(
                        "Limited check: some sources or AI analysis were unavailable, so confidence is capped.",
                        color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(ink.copy(alpha = 0.08f)).padding(12.dp),
                    )
                }
                Section("Claim checked", ink) { Text(result.claim, color = ink, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium) }
                Section("Why", ink) { Text(result.summary, color = ink, fontSize = 15.sp, lineHeight = 22.sp) }

                if (result.claims.size > 1) {
                    Section("Other claims in that line (${result.claims.size})", ink) {
                        result.claims.forEach { c ->
                            Text("${c.claim}: ${c.verdict.label}", color = ink, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }

                Section(if (result.sources.isEmpty()) "Sources" else "Sources (${result.sources.size})", ink) {
                    if (result.sources.isEmpty()) {
                        Text("No independent source covered this claim. Try a line with a clear statement of fact.", color = ink, fontSize = 14.sp)
                    }
                    result.sources.forEachIndexed { i, src ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ink.copy(alpha = 0.12f)))
                        SourceRow(src, ink, onOpen)
                    }
                }

                Text(
                    "deBUNK is an automated aid, not a ruling from an authority. Read the sources before you share.",
                    color = ink.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 17.sp,
                )
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(ink)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("Close", color = paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Section(title: String, ink: Color, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = ink.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SourceRow(s: SourceRef, ink: Color, onOpen: (String) -> Unit) {
    // Stance is spelled out in words and a symbol, never colour alone.
    val stance = when (s.stance) {
        Stance.SUPPORTS -> "\u2713 Supports"
        Stance.CONTRADICTS -> "\u2717 Contradicts"
        Stance.NEUTRAL -> "? Doesn't confirm or deny"
    }
    val kind = when (s.kind) { "fact_check" -> "Fact-check"; "official" -> "Official"; else -> "News" }
    val meta = listOfNotNull(kind, s.published?.take(10), "tier ${s.credibilityTier}").joinToString(", ")
    val canOpen = s.url.startsWith("https://") || s.url.startsWith("http://")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = canOpen) { onOpen(s.url) }.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(s.name, color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(stance, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        s.title?.let { Text(it, color = ink, fontSize = 14.sp, lineHeight = 19.sp) }
        Text(meta + if (canOpen) ". Tap to open" else "", color = ink.copy(alpha = 0.65f), fontSize = 12.sp)
    }
}
