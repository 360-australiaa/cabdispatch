package au.com.threesixty.cabdispatch.ui.screens.hired

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.data.remote.GeocodeResult
import au.com.threesixty.cabdispatch.domain.format.asLocalTime
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.Type
import au.com.threesixty.cabdispatch.ui.theme.ChakraPetch
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The navigator half of the meter screen: the destination search bar and dialog with its
 * address keypad and suggestion rows, the turn banner, stop line and bottom bar, and the
 * distance/duration/ETA formatting these share.
 *
 * Extracted from HiredScreen.kt in Phase 0 (P0.4) — a mechanical move, same package, same
 * declarations, no behaviour change.
 */

private val ETA_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/** "1.2 km" / "350 m" / "—" for a null distance — the navigator's remaining-distance readout. */
private fun formatDistanceM(m: Double?): String {
    if (m == null || m.isNaN()) return "—"
    return if (m >= 1000) "%.1f km".format(m / 1000.0) else "${m.roundToInt()} m"
}

/** "1h 05m" / "12 min" / "—" for a null duration — the navigator's remaining-time readout. */
private fun formatDurationS(s: Double?): String {
    if (s == null || s.isNaN()) return "—"
    val totalMin = (s / 60.0).roundToInt()
    return if (totalMin >= 60) "${totalMin / 60}h %02dm".format(totalMin % 60) else "$totalMin min"
}

/** "4:32 PM" / "—" for a null ETA — same clock format as [asLocalTime], just from an epoch millis
 * (the navigator's own field) rather than an ISO-8601 string. */
private fun formatEtaClock(epochMillis: Long?): String {
    if (epochMillis == null) return "—"
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(ETA_TIME_FORMATTER)
    }.getOrDefault("—")
}

/**
 * The primary, always-visible destination entry point (2026-09-05 pass): an optional "Enter
 * destination" field with a search icon, sitting directly on the map panel — visible for the
 * entire time a fare is running, never gated behind [ControlsHandle]/[ControlsDrawer]'s MORE tile.
 * Direct user correction: destination search was "multiple taps deep" before this; this affordance
 * is now the first thing on the map panel, right where the trip/nav status pills already sit.
 *
 * Genuinely optional — an empty field just shows placeholder text, never a validation error or a
 * nag — and it never types inline: tapping it (whatever its current label) opens the exact same
 * [DestinationSearchDialog] this screen already had, wired to the exact same
 * [MeterNavViewModel.onQueryChange]/[MeterNavViewModel.selectDestination] calls (see that dialog's
 * own doc for why address entry there is the hand-rolled [AddressKeypad], not a system
 * `TextField` — the identical constraint applies to any text entry on this screen, so this bar is a
 * button-shaped launcher for that dialog rather than a second, competing text-input
 * implementation). [destinationLabel] is the real [MeterNavUiState.destination]'s place name once
 * one is picked (replacing the placeholder); [onClear] is non-null (and renders a small clear
 * icon) only once a destination actually exists, so an empty/optional field never shows a
 * clear affordance with nothing to clear.
 */
@Composable
internal fun MapDestinationSearchBar(
    destinationLabel: String?,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 220.dp, max = 340.dp)
            // 46dp -> 48dp (A4, 2026-09-08). Two dp, and the only reason it is worth a line of
            // comment: at 46 this bar was *below* the Material minimum touch target while looking
            // for all the world like it met it, which is precisely the kind of near-miss that
            // never gets caught by eye.
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CaptainPalette.panel.copy(alpha = 0.92f))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, contentDescription = "Search for a destination", tint = CaptainPalette.hudAccent, modifier = Modifier.size(18.dp))
        Text(
            destinationLabel ?: "Enter destination (optional)",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = if (destinationLabel == null) CaptainPalette.textMuted else CaptainPalette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        if (onClear != null) {
            // 15dp -> a 48dp target (A4, 2026-09-08). This was the single smallest hit area in the
            // app: a 15dp glyph with the clickable ON the glyph, and 6dp of that was padding
            // inside the hit rect. The icon is unchanged at 15dp; the box around it is the target.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Clear destination",
                    tint = CaptainPalette.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * A real directional icon for the current maneuver, derived ONLY from Mapbox's own
 * `maneuver.type`/`maneuver.modifier` fields ([RouteStep.maneuverType]/[RouteStep.modifier],
 * parsed in [MapboxDirections.parseRoute]) — never inferred by pattern-matching
 * [RouteStep.instruction] text, and never a generic/default arrow when the data doesn't support
 * one (returns `null`, so the caller falls back to the instruction text alone).
 *
 * `depart`/`arrive` get fixed icons (a maneuver TYPE, not a laterality — nothing to guess).
 * `roundabout`/`rotary`/`fork` need a real `left`/`right` modifier for their directional icon
 * (Mapbox does supply one for these); anything else on them falls through to the generic
 * modifier-only mapping below. `merge` has no left/right icon in this project's icon set, so it
 * gets one direction-neutral glyph regardless of modifier — that is not a guess, it is the
 * accurate icon for "merge" full stop. `uturn` has no laterality in Mapbox's data at all (the
 * modifier is just `"uturn"`), and this icon set's only u-turn glyphs are direction-specific
 * (`UTurnLeft`/`UTurnRight`) — picking either would assert a direction the API never gave, so it
 * intentionally maps to `null` (text only) rather than a distinct icon.
 */
internal fun maneuverIcon(maneuverType: String?, modifier: String?): ImageVector? = when {
    maneuverType == "arrive" -> Icons.Rounded.Flag
    maneuverType == "depart" -> Icons.Rounded.DirectionsCar
    (maneuverType == "roundabout" || maneuverType == "rotary" || maneuverType == "roundabout turn") && modifier == "left" -> Icons.Rounded.RoundaboutLeft
    (maneuverType == "roundabout" || maneuverType == "rotary" || maneuverType == "roundabout turn") && modifier == "right" -> Icons.Rounded.RoundaboutRight
    maneuverType == "fork" && modifier == "left" -> Icons.Rounded.ForkLeft
    maneuverType == "fork" && modifier == "right" -> Icons.Rounded.ForkRight
    maneuverType == "merge" -> Icons.Rounded.Merge
    modifier == "left" -> Icons.Rounded.TurnLeft
    modifier == "right" -> Icons.Rounded.TurnRight
    modifier == "slight left" -> Icons.Rounded.TurnSlightLeft
    modifier == "slight right" -> Icons.Rounded.TurnSlightRight
    modifier == "sharp left" -> Icons.Rounded.TurnSharpLeft
    modifier == "sharp right" -> Icons.Rounded.TurnSharpRight
    modifier == "straight" -> Icons.Rounded.Straight
    else -> null
}

/**
 * The prominent top-of-map turn-by-turn banner (2026-09-05 redesign — direct correction: "can't
 * see turn by turn guidance", because the maneuver used to be a 10sp subtext line inside the
 * bottom-corner DESTINATION card). Shown only once [MeterNavUiState.route] is non-null — big real
 * turn icon ([maneuverIcon], never guessed), the actual spoken instruction
 * ([navState.currentInstruction]) as the headline, and the real "next turn in Xm" readout
 * ([navState.distanceToNextManeuverM] — [NavProgress.distanceToCurrentManeuverM], distinct from
 * the whole-trip remaining distance) as the number a driver glancing over actually needs.
 */
@Composable
internal fun NavTurnBanner(navState: MeterNavUiState, modifier: Modifier = Modifier) {
    val currentStep = navState.route?.steps?.getOrNull(navState.currentStepIndex)
    val icon = maneuverIcon(currentStep?.maneuverType, currentStep?.modifier) ?: Icons.Rounded.Straight
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadiusDp = 18, glow = CaptainPalette.hudAccent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(CaptainPalette.hudAccent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = "Next turn", tint = CaptainPalette.hudAccent, modifier = Modifier.size(28.dp))
            }
            Text(
                navState.currentInstruction ?: "Continue on route",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = CaptainPalette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp).weight(1f),
            )
            Text(
                formatDistanceM(navState.distanceToNextManeuverM),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = CaptainPalette.hudAccent,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** One line of [NavBottomBar]'s route summary: a small tinted pin icon + a single-line ellipsized
 * address. Shared shell between the PICK UP and DESTINATION rows. */
@Composable
private fun NavStopLine(icon: ImageVector, tone: Color, address: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
        // and Compose merges this node's semantics into one announcement -- a description here
        // would make TalkBack read the same words twice. The audit's finding was unlabelled
        // *controls*; every control on this screen now carries a real name. This is a reviewed
        // null, not an overlooked one.
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(13.dp))
        Text(
            address,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
            style = Type.tiny,
            color = CaptainPalette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * The map panel's entire bottom-edge nav surface, as one slim glass bar (2026-09-05 redesign —
 * direct correction: "pickup and destination is hiding the background of the map"). Replaces the
 * old three-stacked-card block (a PICK UP/DESTINATION row, a separate ETA strip, a separate OPEN
 * NAVIGATION row — together costing ~190dp of map real estate) with one row costing roughly a
 * third of that; every field is the same [MeterNavUiState] those three composables read, none of
 * it re-derived.
 *
 * Left: PICK UP ([navState.pickupAddress] — real once [MeterNavViewModel.resolvePickupAddress]
 * resolves it, "—" until then, never fabricated) over DESTINATION ([navState.destination]) with
 * CHANGE/clear inline. Middle: exactly one of [navState.routing]/[navState.routeError]/
 * [navState.route]'s DISTANCE-ETA-ARRIVE stats/"no route yet" — the same four honest states
 * [RouteEtaPanel] used to render. Right: OPEN NAVIGATION + the voice toggle.
 */
@Composable
internal fun NavBottomBar(
    navState: MeterNavUiState,
    onChange: () -> Unit,
    onClear: () -> Unit,
    onRetryRoute: () -> Unit,
    onOpenNavigation: () -> Unit,
    speechEnabled: Boolean,
    onToggleVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val destination = navState.destination
    GlassCard(modifier = modifier, cornerRadiusDp = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false).widthIn(max = 220.dp)) {
                NavStopLine(icon = Icons.Rounded.Place, tone = CaptainPalette.success, address = navState.pickupAddress ?: "—")
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NavStopLine(
                        icon = Icons.Rounded.Flag,
                        tone = CaptainPalette.danger,
                        address = destination?.placeName ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "CHANGE",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        // 9sp -> 12sp (A4: Type.tiny, the accessibility floor).
                        style = Type.tiny,
                        color = CaptainPalette.hudSweepMid,
                        // A8 a11y pass: `minimumInteractiveComponentSize()` pads the hit rect out
                        // to the 48dp minimum on all sides without growing the visible glyph or
                        // this slim nav bar's own height — the same trick Material3 buttons use
                        // internally. Row order is unaffected; it only widens the invisible target.
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onChange)
                            .semantics { role = Role.Button },
                    )
                    // A8 a11y pass: was a bare 13dp icon with the clickable on the glyph itself —
                    // ~19dp of hit area, well under the 48dp minimum, and unlabelled/no button
                    // role for TalkBack. Sibling `onClear` above (line ~163) already got the full
                    // 48dp treatment (A4); this one was missed. Same `minimumInteractiveComponentSize()`
                    // approach as CHANGE above so the compact bar doesn't grow visually.
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear destination",
                        tint = CaptainPalette.textMuted,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onClear)
                            .semantics { role = Role.Button }
                            .size(13.dp),
                    )
                }
            }
            Box(modifier = Modifier.padding(horizontal = 12.dp).width(1.dp).height(32.dp).background(CaptainPalette.panelBorder))
            Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                when {
                    navState.routing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), color = CaptainPalette.hudAccent, strokeWidth = 2.dp)
                        Text(
                            "Finding route…",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium,
                            // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
                            style = Type.tiny,
                            color = CaptainPalette.textSecondary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    navState.routeError != null -> {
                        Text(
                            navState.routeError,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium,
                            // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
                            style = Type.tiny,
                            color = CaptainPalette.danger,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp).padding(end = 6.dp),
                        )
                        // 11sp -> 12sp and 32dp -> 48dp (A4): CaptainButton takes its own fontSize/heightDp
                        // rather than a TextStyle, so the floor is applied by value here.
                        CaptainButton(text = "RETRY", outline = true, heightDp = 48, fontSize = 12.sp, widthDp = 68, onClick = onRetryRoute)
                    }
                    navState.route != null -> {
                        MiniEtaStat("DISTANCE", formatDistanceM(navState.remainingDistanceM))
                        Spacer(Modifier.width(12.dp))
                        MiniEtaStat("ETA", formatDurationS(navState.remainingDurationS))
                        Spacer(Modifier.width(12.dp))
                        MiniEtaStat("ARRIVE", formatEtaClock(navState.etaEpochMillis))
                    }
                    else -> Text(
                        "No route yet",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Medium,
                        // 11sp -> 12sp (A4: Type.tiny, the accessibility floor).
                        style = Type.tiny,
                        color = CaptainPalette.textMuted,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CaptainButton(
                text = "NAVIGATE",
                heightDp = 42,
                widthDp = 108,
                fontSize = 12.sp,
                enabled = destination != null,
                onClick = onOpenNavigation,
            )
            Spacer(Modifier.width(8.dp))
            SpeechToggleButton(enabled = speechEnabled, onToggle = onToggleVoice)
        }
    }
}

@Composable
private fun MiniEtaStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp, color = CaptainPalette.textMuted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary, modifier = Modifier.padding(top = 1.dp))
    }
}

// ============================================================================================
// Destination search — reached from [MapDestinationSearchBar] (the always-visible map-panel
// entry point, both mockups), [ControlsDrawer]'s MORE tile, or CHANGE (mockup #4's DESTINATION
// card) — all three open this identical dialog, never a duplicate search implementation.
// ============================================================================================

/**
 * The real destination search — [MeterNavViewModel.onQueryChange] drives the debounced Mapbox
 * Geocoding lookup, [nav.suggestions] is whatever it really returned, [nav.searching]/
 * [nav.searchError] are its real loading/failure states. Selecting a row calls
 * [onSelect] → [MeterNavViewModel.selectDestination] (the caller then dismisses this dialog,
 * which flips [MeterNavUiState.destination] non-null and switches the whole screen to mockup #4).
 *
 * **No system text field.** Every other on-device text entry in this app — driver #/PIN sign-in,
 * vehicle rego (`LoginVehicleBindScreen.kt`'s `AlphaNumPad`/`RegoKeyRows`) — is typed on a
 * hand-rolled on-screen keyboard, never the platform IME: on-device verification of this exact
 * dialog confirmed why (tapping a real `TextField` here never brought up a soft keyboard on the
 * target tablet — `dumpsys input_method` showed `mShowRequested=false` even with a Compose input
 * connection attached, and `adb shell input text`/`keyevent` landed nowhere). Building this dialog
 * on a system `TextField`, the one screen in this pass reaching for it, would have shipped a
 * control a real driver could never type into on the real hardware. [AddressKeypad] below is the
 * same on-screen-keyboard idiom as the rest of the app, sized for full address text (letters,
 * digits, space) rather than a short code. Every keystroke still goes through the identical
 * `onQueryChange(String)` call a system field would have made — [MeterNavViewModel] cannot tell
 * the difference.
 *
 * Same dialog shell as every other action on this screen ([TollPresetDialog], [SetPriceInfoDialog]
 * etc.) — no result is ever invented: an empty/erroring search renders exactly that, honestly.
 */
@Composable
internal fun DestinationSearchDialog(
    nav: MeterNavUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (GeocodeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(CaptainPalette.panel)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(24.dp))
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (nav.destination != null) "Change destination" else "Set destination",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = CaptainPalette.textPrimary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CaptainPalette.inset)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
                // and Compose merges this node's semantics into one announcement -- a description here
                // would make TalkBack read the same words twice. The audit's finding was unlabelled
                // *controls*; every control on this screen now carries a real name. This is a reviewed
                // null, not an overlooked one.
                Icon(Icons.Rounded.Search, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(18.dp))
                Text(
                    nav.query.ifEmpty { "Search an address…" },
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (nav.query.isEmpty()) CaptainPalette.textMuted else CaptainPalette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                if (nav.searching) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CaptainPalette.hudAccent, strokeWidth = 2.dp)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 190.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    nav.query.isBlank() -> SearchHintLine("Type at least 3 characters to search.")
                    nav.searchError != null && nav.suggestions.isEmpty() -> SearchHintLine(nav.searchError, color = CaptainPalette.danger)
                    nav.suggestions.isEmpty() && !nav.searching -> SearchHintLine("No matches found.")
                    else -> nav.suggestions.forEach { result ->
                        SuggestionRow(result = result, onClick = { onSelect(result) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(text = "Close", outline = true, modifier = Modifier.fillMaxWidth()) { onDismiss() }
        }
        AddressKeypad(
            onKey = { c -> onQueryChange(nav.query + c) },
            onSpace = { onQueryChange("${nav.query} ".trimStart()) },
            onBackspace = { onQueryChange(nav.query.dropLast(1)) },
            onClear = { onQueryChange("") },
        )
    }
}

/** Six-per-row alphabet+digit layout (26 letters + 10 digits fill exactly six rows of six) plus a
 * wide SPACE / BACKSPACE / CLR row — the same on-screen-keyboard shape [AlphaNumPad] in
 * `LoginVehicleBindScreen.kt` uses for driver #/rego entry, sized here for a full street address
 * rather than a short code. A private, small duplicate rather than a cross-file reach into that
 * `private` composable. */
@Composable
internal fun AddressKeypad(onKey: (Char) -> Unit, onSpace: () -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    val rows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c -> AddressKey(label = c.toString(), modifier = Modifier.size(48.dp), onClick = { onKey(c) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddressKey(label = "SPACE", modifier = Modifier.width(216.dp).height(48.dp), onClick = onSpace)
            AddressKey(modifier = Modifier.size(48.dp), onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace", tint = CaptainPalette.warning, modifier = Modifier.size(18.dp))
            }
            AddressKey(label = "CLR", modifier = Modifier.size(48.dp), accent = true, onClick = onClear)
        }
    }
}

@Composable
private fun AddressKey(
    label: String? = null,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CaptainPalette.raised)
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            label != null -> Text(
                label,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (label.length > 1) 12.sp else 18.sp,
                color = if (accent) CaptainPalette.warning else CaptainPalette.textPrimary,
            )
        }
    }
}

@Composable
private fun SearchHintLine(text: String, color: Color = CaptainPalette.textSecondary) {
    Text(text, fontFamily = InterFamily, fontSize = 13.sp, color = color, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SuggestionRow(result: GeocodeResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative (A4 a11y pass, reviewed): the text beside this glyph already IS its label,
        // and Compose merges this node's semantics into one announcement -- a description here
        // would make TalkBack read the same words twice. The audit's finding was unlabelled
        // *controls*; every control on this screen now carries a real name. This is a reviewed
        // null, not an overlooked one.
        Icon(Icons.Rounded.Place, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(result.shortName, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = CaptainPalette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(result.placeName, fontFamily = InterFamily, fontSize = 12.sp, color = CaptainPalette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
