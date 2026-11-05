package com.debunk.app.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debunk.core.domain.DotState
import com.debunk.core.domain.PanelMode
import com.debunk.core.domain.Stance
import com.debunk.core.domain.Verdict
import com.debunk.core.domain.VerdictResult

// ---------------------------------------------------------------------------------------------
// Palette. State is NEVER color-only: every state also has an icon (check / cross / question mark)
// and every verdict has a text label.
// ---------------------------------------------------------------------------------------------
internal val Grey = Color(0xFF8A8FA8)
internal val Blue = Color(0xFF3B3FE0)      // "pen" indigo: brand colour and the verifying state
internal val Green = Color(0xFF0F8A5F)
internal val Red = Color(0xFFC9302C)
internal val Amber = Color(0xFFE8A317)
private val Ink = Color(0xFF14172B)
private val Paper = Color(0xFFF7F8FC)
private val InkDark = Color(0xFFECEEFA)
private val PaperDark = Color(0xFF191C33)

internal fun DotState.color() = when (this) {
    DotState.IDLE -> Grey
    DotState.VERIFYING -> Blue
    DotState.TRUE -> Green
    DotState.FALSE -> Red
    DotState.CAUTION -> Amber
}

private fun DotState.glyphColor() = if (this == DotState.CAUTION) Ink else Color.White

private fun DotState.description() = when (this) {
    DotState.IDLE -> "deBUNK idle"
    DotState.VERIFYING -> "deBUNK is verifying a news claim"
    DotState.TRUE -> "Claim likely true. Tap for details"
    DotState.FALSE -> "Claim likely false. Tap for details"
    DotState.CAUTION -> "Claim misleading or unverified. Tap for details"
}

// ---------------------------------------------------------------------------------------------
// Anchor dot
// ---------------------------------------------------------------------------------------------

/** 48dp touch target containing a 20dp visible dot. Pulses while verifying. */
@Composable
fun StatusDot(state: DotState) {
    val dark = isSystemInDarkTheme()
    Box(
        Modifier.size(48.dp).semantics { contentDescription = state.description() },
        contentAlignment = Alignment.Center,
    ) {
        // The infinite animation only exists while verifying, so an idle dot costs no frames (battery).
        if (state == DotState.VERIFYING) PulseRing()
        Box(
            Modifier.size(20.dp).graphicsLayer { alpha = if (state == DotState.IDLE) 0.7f else 1f }
                .drawBehind {
                    // outer ring (visible on light AND dark backgrounds), inner ring, state-colored core
                    val r = size.minDimension / 2
                    drawCircle(if (dark) InkDark else Ink, radius = r)
                    drawCircle(if (dark) Ink else Color.White, radius = r - 1.dp.toPx())
                    drawCircle(state.color(), radius = r - 3.dp.toPx())
                },
            contentAlignment = Alignment.Center,
        ) { StateGlyph(state, 9.dp) }
    }
}

@Composable
private fun PulseRing() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "pulseScale",
    )
    Box(
        Modifier.size(20.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = (1.7f - scale).coerceIn(0f, 1f) * 0.6f }
            .drawBehind { drawCircle(Blue) },
    )
}

/** Check / cross / question-mark glyph. Drawn on a Canvas so it stays crisp at 9dp. */
@Composable
fun StateGlyph(state: DotState, size: Dp) {
    val color = state.glyphColor()
    when (state) {
        DotState.TRUE -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            val p = Path().apply {
                moveTo(w * 0.08f, w * 0.55f); lineTo(w * 0.38f, w * 0.85f); lineTo(w * 0.94f, w * 0.15f)
            }
            drawPath(p, color, style = Stroke(w * 0.22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        DotState.FALSE -> Canvas(Modifier.size(size)) {
            val w = this.size.width
            val s = Stroke(w * 0.22f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), s.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.88f, w * 0.12f), Offset(w * 0.12f, w * 0.88f), s.width, StrokeCap.Round)
        }
        DotState.CAUTION -> Text("?", color = color, fontSize = (size.value * 1.35f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (size.value * 1.35f).sp)
        else -> Unit
    }
}

// ---------------------------------------------------------------------------------------------
// Connector line (own full-screen, non-touchable window; draws in screen coordinates)
// ---------------------------------------------------------------------------------------------

data class LineSpec(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

/** Thin 2dp line that "draws on" from the dot to the box corner, and fades out with the box. */
@Composable
fun ConnectorLine(spec: LineSpec?, shown: Boolean) {
    if (spec == null) return
    val dark = isSystemInDarkTheme()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(spec) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "lineAlpha")
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val a = Offset(spec.startX, spec.startY)
        val b = Offset(spec.endX, spec.endY)
        drawLine(
            color = Blue,
            start = a,
            end = lerp(a, b, progress.value),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

// ---------------------------------------------------------------------------------------------
// Message box + quick menu
// ---------------------------------------------------------------------------------------------

interface PanelActions {
    fun onDetails()
    fun onDismiss()
    fun onSourcesExpanded()
    fun onPause()
    fun onIgnoreApp()
    fun onSettings()
    fun onUpgrade()
}

/** Fades the current panel (verdict box or quick menu) in/out. */
@Composable
fun PanelContent(
    shown: Boolean,
    mode: PanelMode,
    result: VerdictResult?,
    widthDp: Dp,
    maxHeightDp: Dp,
    actions: PanelActions,
) {
    AnimatedVisibility(visible = shown, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        when (mode) {
            PanelMode.VERDICT -> if (result != null) VerdictBox(result, widthDp, maxHeightDp, actions)
            PanelMode.MENU -> QuickMenu(widthDp, actions)
            PanelMode.PAYWALL -> PaywallBox(widthDp, actions)
            PanelMode.NONE -> Unit
        }
    }
}

/** The proof slip: rounded paper, a thin ink outline and a thick spine in the verdict colour on the left. */
private fun Modifier.boxFrame(dark: Boolean, spine: Color): Modifier = drawBehind {
    val corner = CornerRadius(10.dp.toPx())
    val stroke = 1.5.dp.toPx()
    val ink = if (dark) InkDark else Ink
    drawRoundRect(if (dark) PaperDark else Paper, cornerRadius = corner)
    // spine: clip to the rounded rect by drawing a rounded rect, then covering its right edge with paper
    val spineW = 8.dp.toPx()
    drawRoundRect(spine, size = Size(spineW + corner.x, size.height), cornerRadius = corner)
    drawRect(if (dark) PaperDark else Paper, topLeft = Offset(spineW, 0f), size = Size(corner.x, size.height))
    drawRoundRect(
        color = ink.copy(alpha = 0.85f),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = corner,
        style = Stroke(stroke),
    )
}

@Composable
private fun VerdictBox(result: VerdictResult, widthDp: Dp, maxHeightDp: Dp, actions: PanelActions) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) InkDark else Ink
    var sourcesOpen by remember { mutableStateOf(false) }
    val dot = when (result.verdict) {
        Verdict.LIKELY_TRUE -> DotState.TRUE
        Verdict.LIKELY_FALSE -> DotState.FALSE
        else -> DotState.CAUTION
    }

    Column(
        Modifier.width(widthDp).heightIn(max = maxHeightDp).boxFrame(dark, dot.color())
            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            // Headline: icon, verdict word, and the confidence as the one big number.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).drawBehind { drawCircle(dot.color()) },
                    contentAlignment = Alignment.Center,
                ) { StateGlyph(dot, 10.dp) }
                Spacer(Modifier.width(8.dp))
                Text(
                    result.verdict.label, color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("${result.confidence}%", color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            }
            if (result.developing) {
                Text("Developing story, may change", color = ink.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(result.summary, color = ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val names = result.sources.map { it.name }.distinct()
            val shownNames = if (names.isEmpty()) "none found"
            else names.take(3).joinToString(", ") + if (names.size > 3) " +${names.size - 3}" else ""
            Text(
                "Sources: $shownNames" + if (sourcesOpen) "  \u25B2" else "  \u25BC",
                color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable {
                    sourcesOpen = !sourcesOpen
                    if (sourcesOpen) actions.onSourcesExpanded()
                }.padding(vertical = 8.dp),
            )
            if (sourcesOpen) {
                result.sources.forEach { s ->
                    val stance = when (s.stance) {
                        Stance.SUPPORTS -> "\u2713 supports"
                        Stance.CONTRADICTS -> "\u2717 contradicts"
                        Stance.NEUTRAL -> "? neutral"
                    }
                    Text("${s.name}: $stance", color = ink, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxButton("See details", filled = true, ink = ink, dark = dark, modifier = Modifier.weight(1f), onClick = actions::onDetails)
            BoxButton("Dismiss", filled = false, ink = ink, dark = dark, modifier = Modifier.weight(1f), onClick = actions::onDismiss)
        }
    }
}

@Composable
private fun BoxButton(label: String, filled: Boolean, ink: Color, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fg = if (filled) (if (dark) PaperDark else Paper) else ink
    Box(
        modifier.heightIn(min = 44.dp).clickable(onClick = onClick).drawBehind {
            val r = CornerRadius(8.dp.toPx())
            if (filled) drawRoundRect(ink, cornerRadius = r)
            else drawRoundRect(ink.copy(alpha = 0.6f), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
        },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
private fun QuickMenu(widthDp: Dp, actions: PanelActions) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) InkDark else Ink
    Column(Modifier.width(widthDp).boxFrame(dark, Blue).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
        listOf<Pair<String, () -> Unit>>(
            "Pause deBUNK" to actions::onPause,
            "Ignore this app" to actions::onIgnoreApp,
            "Open settings" to actions::onSettings,
        ).forEach { (label, action) ->
            Text(
                label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = action)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            )
        }
    }
}

/** Shown instead of a verdict when the free daily checks are used up. Calm, says what happened and what to do. */
@Composable
private fun PaywallBox(widthDp: Dp, actions: PanelActions) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) InkDark else Ink
    Column(
        Modifier.width(widthDp).boxFrame(dark, Blue)
            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Text("You've used today's free checks", color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Free checks reset tomorrow. Go premium for unlimited checks.",
            color = ink, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxButton("Go premium", filled = true, ink = ink, dark = dark, modifier = Modifier.weight(1f), onClick = actions::onUpgrade)
            BoxButton("Not now", filled = false, ink = ink, dark = dark, modifier = Modifier.weight(1f), onClick = actions::onDismiss)
        }
    }
}
