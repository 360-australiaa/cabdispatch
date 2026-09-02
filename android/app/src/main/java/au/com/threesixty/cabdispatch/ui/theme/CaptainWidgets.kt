package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.AppContainer

/**
 * Shared "Captain Taxis" purple-design atoms — lifted out of
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen] (where they were first
 * built and proven, as `private` composables, for the Home dashboard redesign) so every other
 * screen in the app can reuse the exact same button/avatar/SOS/animation building blocks while
 * being migrated off the old yellow/black `Deck` (and, for the five embedded wheel-content panes,
 * the separate glass/gold `WheelColorsV2`) palette onto [CaptainPalette].
 *
 * Extraction is behavior-preserving: every default parameter value below matches the exact
 * hardcoded value the composable had inline in `DeckHomeScreen.kt`, so Home's own rendering is
 * unchanged by this move. New optional parameters (e.g. [DriverAvatar]'s `sizeDp`, [SosControl]'s
 * `label`/`holdLabel`/`sizeDp`) exist only so other screens can reuse these at a different scale —
 * they don't alter Home's call sites, which don't pass them.
 */

/**
 * Loads the signed-in driver's real uploaded photo (`GET /v1/users/{userId}/photo`) — same
 * endpoint and Bitmap-decode approach
 * [au.com.threesixty.cabdispatch.ui.screens.profile.ProfileViewModel.loadPhoto] already uses,
 * reimplemented here as a screen-local loader rather than by editing that ViewModel. Falls back to
 * the driver's initials on no-photo/offline/error — never a stock/generic image standing in for a
 * real person. [driverId] as the `remember` key: a factory-reset/re-login mid-process must not
 * show a stale photo for a different driver.
 */
@Composable
fun DriverAvatar(driverId: String?, driverName: String?, onClick: () -> Unit, sizeDp: Int = 72) {
    var photo by remember(driverId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(driverId) {
        photo = null
        val id = driverId ?: return@LaunchedEffect
        runCatching {
            AppContainer.apiService.getUserPhoto(id).byteStream().use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull()?.let { photo = it }
    }
    Box(
        modifier = Modifier.size(sizeDp.dp).clip(CircleShape).background(CaptainPalette.raised)
            .border(1.5.dp, CaptainPalette.panelBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = photo
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val initials = driverName?.split(" ")?.mapNotNull { it.firstOrNull()?.uppercase() }?.take(2)?.joinToString("") ?: "—"
            Text(initials, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = (sizeDp / 3).sp, color = CaptainPalette.textPrimary)
        }
    }
}

/** A status dot that breathes (scale+alpha loop) while [animated] — used for "this is live/active"
 * signals (availability). Cheap infinite transition, same shape the meter dial's glow uses. */
@Composable
fun PulsingDot(color: androidx.compose.ui.graphics.Color, animated: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 10.dp) {
    val pulse by rememberInfiniteFloat(animated, from = 0.75f, to = 1f, durationMs = 1100)
    Box(
        modifier = modifier.size(size).scale(if (animated) pulse else 1f).clip(CircleShape).background(color),
    )
}

/** Small helper so every "breathing" animation across the app (pulsing dot, SOS glow, meter-dial
 * glow) shares one reverse-repeating tween instead of hand-rolling `rememberInfiniteTransition` at
 * each call site. Returns a constant [to] (never animating) when [enabled] is false, so a
 * paused/idle state genuinely stops animating rather than freezing mid-pulse. */
@Composable
fun rememberInfiniteFloat(enabled: Boolean, from: Float, to: Float, durationMs: Int): androidx.compose.runtime.State<Float> {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    return if (enabled) {
        transition.animateFloat(
            initialValue = from,
            targetValue = to,
            animationSpec = infiniteRepeatable(tween(durationMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse-v",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(to) }
    }
}

/**
 * SOS / emergency control — visually a glowing red-ringed circular badge, but keeps this app's
 * real duress safety property: press-and-**hold**, never a tap. A slow breathing glow (not a fast
 * alarm-style flash) signals "armed", not "already firing" — urgency is reserved for the real
 * `DuressUiState.Triggered` overlay once it actually fires. [label]/[holdLabel]/[sizeDp] are
 * cosmetic-only parameters added for reuse elsewhere (e.g. a differently-labelled duress control
 * on the meter screen); the interaction model (`combinedClickable(onLongClick = onTrigger)`, no
 * `onClick` action) must never be changed to a tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosControl(
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "SOS",
    holdLabel: String = "HOLD",
    sizeDp: Int = 72,
) {
    val glow by rememberInfiniteFloat(enabled = true, from = 0.35f, to = 0.85f, durationMs = 1400)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .border(2.5.dp, CaptainPalette.danger.copy(alpha = glow), CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(CaptainPalette.danger.copy(alpha = glow * 0.18f), CaptainPalette.panel),
                    ),
                )
                .combinedClickable(onClick = {}, onLongClick = onTrigger),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CaptainPalette.danger)
        }
        Text(holdLabel, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun StatLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CaptainPalette.textSecondary, modifier = Modifier.size(17.dp))
        Text(text, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textSecondary, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * "Game juice" press feedback as a reusable modifier (2026-08-29 premium pass): touch-down
 * squashes the target with a bouncy spring (it visibly overshoots on release — the arcade
 * button-pop) while an accent glow ring flares up and fades out. One modifier so EVERY tappable
 * surface in the app can share identical feel instead of each screen hand-rolling its own tween.
 * Purely presentational — `onClick` fires exactly as a plain `clickable` would.
 */
@Composable
fun Modifier.gameClick(
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp),
    glowColor: androidx.compose.ui.graphics.Color = CaptainPalette.accent,
    pressScale: Float = 0.93f,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "game-press-scale",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(if (pressed) 50 else 320),
        label = "game-press-glow",
    )
    return this
        .scale(scale)
        .then(if (glow > 0.01f) Modifier.border(2.dp, glowColor.copy(alpha = 0.75f * glow), shape) else Modifier)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * The workhorse Captain-Taxis button — press-scale + brief glow "game HUD" tactile feedback: a
 * button visibly depresses on touch-down rather than only reacting on release. `heightDp` default
 * of 64 clears Android's 48dp accessibility minimum with real margin, not by a hair — every screen
 * migrating onto this button should use at least this default, never something smaller.
 * 2026-08-29 game-feel upgrade: the press scale is now a bouncy spring (overshoots on release)
 * and touch-down flares an accent glow ring — same juice as [gameClick], applied here so every
 * CaptainButton in the app pops identically.
 */
@Composable
fun CaptainButton(
    text: String,
    modifier: Modifier = Modifier,
    widthDp: Int? = null,
    heightDp: Int = 64,
    fontSize: androidx.compose.ui.unit.TextUnit = 20.sp,
    outline: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 900f),
        label = "btn-press",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(if (pressed) 50 else 320),
        label = "btn-glow",
    )
    Box(
        modifier = modifier
            .let { if (widthDp != null) it.width(widthDp.dp) else it }
            .height(heightDp.dp)
            .scale(scale)
            .clip(shape)
            .then(
                if (outline) {
                    Modifier.border(1.5.dp, CaptainPalette.accent, shape).background(CaptainPalette.bg)
                } else {
                    Modifier.background(
                        if (pressed && enabled) CaptainPalette.primary.copy(alpha = 0.85f) else CaptainPalette.primary,
                    )
                },
            )
            .then(if (glow > 0.01f) Modifier.border(2.5.dp, CaptainPalette.accent.copy(alpha = 0.85f * glow), shape) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = if (outline) CaptainPalette.accent else CaptainPalette.textPrimary,
        )
    }
}

/**
 * Full-bleed title+back-arrow shell for a screen or embedded pane — a real 48dp touch target
 * around the back glyph, not just the glyph itself. Originally built for the five reused
 * wheel-content panes embedded in Home ([au.com.threesixty.cabdispatch.ui.wheel.content.AvailableTripsWheelContent],
 * `MessagesWheelContent`, `TripsWheelContent`, `EarningsWheelContent`, `ShiftWheelContent`,
 * Home's own `StatusMapPanel`); reused as the standard header for every routed screen migrating
 * onto the Captain Taxis palette so title+back styling is identical everywhere instead of each
 * screen hand-rolling its own.
 */
@Composable
fun PaneShell(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 24.sp, color = CaptainPalette.textPrimary)
            }
            Text(title, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(start = 16.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(CaptainPalette.panel)
                .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(18.dp))
                .padding(18.dp),
        ) {
            content()
        }
    }
}

/**
 * Shared bordered "card" panel — the raised/inset bordered box pattern reimplemented ad hoc at
 * many call sites in `DeckHomeScreen.kt` (`.clip(RoundedCornerShape(18.dp)).background(panel)
 * .border(1.dp, panelBorder, ...)`). New composable (didn't exist as its own function in
 * `DeckHomeScreen.kt`) so every migrated screen's cards share exactly the same radius/border/fill
 * instead of approximating it per screen.
 */
@Composable
fun CaptainPanel(modifier: Modifier = Modifier, cornerRadiusDp: Int = 18, raised: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (raised) CaptainPalette.raised else CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, shape),
    ) {
        content()
    }
}

/**
 * A tappable label+value chip — generalizes `HiredScreen`'s previous screen-local `ChargeChip`
 * (read-only, e.g. "LEVY (PSL) $0.00") and `TollAddChip` (tappable, e.g. "+ M5 $4.50") into one
 * reusable, elderly-friendly-sized (56dp minimum height) shape so other screens' card-and-chip
 * rows (Close & Pay, zone stats) look identical rather than each screen inventing its own chip.
 */
@Composable
fun CaptainChip(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(if (onClick != null) CaptainPalette.raised else CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(start = 10.dp))
    }
}

/** `CaptainKey` — one keypad key, mirrors [au.com.threesixty.cabdispatch.ui.deck.DeckKey]'s
 * layout/typography exactly but on [CaptainPalette] tokens, at a slightly larger 84dp height for
 * the elderly-friendly standard. */
@Composable
private fun CaptainKey(label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .width(140.dp)
            .height(84.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CaptainPalette.raised)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (label.length > 1) 22.sp else 30.sp,
            color = if (accent) CaptainPalette.danger else CaptainPalette.textPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * The standard 3×4 numeric pad (1–9, backspace, 0, CLR) on [CaptainPalette] tokens — consolidates
 * what were at least four independent keypad implementations across the app (login sign-in,
 * Settings pair-code, Close & Pay cash entry, the Admin PIN gate) into one themed, consistently
 * large-touch-target keypad. Callers own the field state; this just reports key presses.
 */
@Composable
fun CaptainKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rowDigits.forEach { d -> CaptainKey(label = d.toString(), onClick = { onDigit(d) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CaptainKey(label = "⌫", accent = true, onClick = onBackspace)
            CaptainKey(label = "0", onClick = { onDigit('0') })
            CaptainKey(label = "CLR", accent = true, onClick = onClear)
        }
    }
}

/**
 * Full-screen dimming scrim that dismisses on outside tap, wrapping arbitrary dialog [content] —
 * generalizes the dismiss-on-outside-tap pattern Home's nav flyout uses so every screen's dialogs
 * get the same real, working dismiss affordance instead of each screen reimplementing (or, in
 * several existing screens today, omitting) it.
 */
@Composable
fun CaptainDialogScrim(visible: Boolean, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onDismissRequest),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.clickable(enabled = false) {}) {
                content()
            }
        }
    }
}
