package au.com.threesixty.cabdispatch.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Phase B v2 design tokens — exact values from the approved Figma file (fileKey
 * `JhEhok3n9bntRNS5Y1u3Yc`, node `25:3`/`28:9`), used ONLY by the v2 Home dashboard reskin
 * ([au.com.threesixty.cabdispatch.ui.screens.dashboard.HomeDashboardV2] and friends).
 *
 * Deliberately a SEPARATE object from [WheelColors] (the v1 dark-mode tokens still used by the
 * wheel/meter/messages/etc. screens this pass does not touch) — the two token sets use visually
 * similar but numerically DIFFERENT hex values (e.g. v1 `bg` is `#0D0A18`, v2's page-gradient top
 * stop is `#0B0817`), so merging them under one name would silently corrupt whichever screen
 * reads the "wrong" generation's value. Do not add v1 usages here or v2 usages to [WheelColors].
 *
 * Compose has no native "backdrop-blur-behind-content" primitive (that needs a `RenderEffect`
 * sampling already-composited content behind a layer, which isn't exposed for arbitrary Compose
 * subtrees pre-API 31 and isn't wired up as a reusable pattern anywhere else in this codebase —
 * confirmed by grepping the tree for `Modifier.blur(`/`RenderEffect`/`glass`, no hits). Per the
 * task brief's own pragmatic-fallback allowance, every "glass" surface below is approximated with
 * a solid semi-opaque scrim ([glassPanel]) instead of a real blur. TODO(visual polish, needs
 * minSdk 31+ or a custom RenderEffect/AGSL shader): swap in real backdrop blur once either this
 * app's minSdk moves off 29 (see app/build.gradle.kts) or a reusable blur-behind-content
 * composable exists elsewhere in the project.
 */
object WheelColorsV2 {
    // Page background gradient (top -> bottom) — sits behind the map/chrome.
    val pageBgTop = Color(0xFF0B0817)
    val pageBgBottom = Color(0xFF181236)
    val pageBackgroundBrush = Brush.verticalGradient(listOf(pageBgTop, pageBgBottom))

    // Glass panel/chip fill — see class doc for why this is a solid scrim, not a real blur.
    val glassPanel = Color(0xC70D0920) // rgba(13,9,32,0.78)
    val glassBorder = Color(0x24FFFFFF) // rgba(255,255,255,0.14)
    val glassInnerHighlight = Color(0x1FFFFFFF) // rgba(255,255,255,0.12), approximated top edge

    // Gold CTA gradient (top -> bottom) + on-gold text.
    val goldCtaTop = Color(0xFFF7CE3C)
    val goldCtaBottom = Color(0xFFD9A912)
    val goldCtaBrush = Brush.verticalGradient(listOf(goldCtaTop, goldCtaBottom))
    val onGoldCta = Color(0xFF241A05)

    // Green CTA gradient (ON DUTY) + on-green text.
    val greenCtaTop = Color(0xFF3EDFA6)
    val greenCtaBottom = Color(0xFF1FA97A)
    val greenCtaBrush = Brush.verticalGradient(listOf(greenCtaTop, greenCtaBottom))
    val onGreenCta = Color(0xFF042A1C)

    // Dock tiles.
    val steelTileTop = Color(0xFF2A2344)
    val steelTileBottom = Color(0xFF1A1530)
    val steelTileBrush = Brush.verticalGradient(listOf(steelTileTop, steelTileBottom))
    val steelTileText = Color(0xD9FFFFFF) // rgba(255,255,255,0.85)
    val activeTileBorder = Color(0xCCFFE082) // rgba(255,224,130,0.8)

    // Numeric/LED text.
    val amberFigure = Color(0xFFFFC94A)

    // Log-off / destructive pill.
    val dangerBg = Color(0x29EF4444) // rgba(239,68,68,0.16)
    val dangerBorder = Color(0x80EF4444) // rgba(239,68,68,0.5)
    val dangerText = Color(0xFFEF4444)

    // Trip-focus card dots.
    val pickupDot = Color(0xFF3EDFA6)
    val dropoffDot = Color(0xFFF7CE3C)

    // Dock-menu screens pass (My Trips/Plot/Available Trips/Statistics/Messages/Trip
    // History/Navigate) — additional tokens straight from the same Figma file
    // (fileKey JhEhok3n9bntRNS5Y1u3Yc, node-ids under major 34/35) not needed by the Home
    // dashboard reskin above but required to match those 7 screens' "Panel / Content" body chrome.
    val panelGlass = Color(0xB8050409) // rgba(5,4,12,0.72) — the deeper "Panel / Content" scrim
    // (vs. the shallower chip scrim [glassPanel] above); same solid-scrim approximation rationale.
    val rowGlass = Color(0x990D0920) // rgba(13,9,32,0.6) — list-row fill inside a Panel/Content body.
    val rowGlassStrong = Color(0xA60D0920) // rgba(13,9,32,0.65) — Available Trips' slightly denser row fill.
    val rowBorder = Color(0x17FFFFFF) // rgba(255,255,255,0.09) — subtle row border on top of rowGlass.

    // Status pill fills (trip-status chips: ACTIVE/DONE/UPCOMING on My Trips).
    val upcomingPillBrush = steelTileBrush
    val upcomingPillText = Color(0xFFB7B0CF)

    // Chart bars (Statistics' "Earnings by hour").
    val chartBarBrush = Brush.verticalGradient(listOf(Color(0xFFF7CE3C), Color(0xFF9A7708)))

    // Danger/unplot CTA gradient (Plot's "UNPLOT" button — a filled danger button, distinct from
    // the outline-style dangerBg/dangerBorder pill used for LOG OFF above).
    val dangerCtaTop = Color(0xFF5A1F2E)
    val dangerCtaBottom = Color(0xFF3B1420)
    val dangerCtaBrush = Brush.verticalGradient(listOf(dangerCtaTop, dangerCtaBottom))

    // Muted secondary figure text (e.g. Available Trips' "#1382" job reference).
    val mutedFigure = Color(0xFF8F87B8)

    // Success/accept-rate figure (Statistics' "ACCEPT 92%").
    val successFigure = Color(0xFF34D399)

    // Bevel highlight overlay for gold/green "hardware-key" pill buttons — a lightweight
    // approximation of the spec's two-layer inset-shadow bevel (see class doc): a bright,
    // fading-out highlight across the top ~55% of the button reads as a raised/lit top edge,
    // layered as a second Box over the base gradient rather than attempting exact inset-shadow
    // math (Compose's `shadow`/`border` APIs don't support inset shadows directly).
    val bevelHighlightBrush = Brush.verticalGradient(
        colors = listOf(Color(0x38FFFFFF), Color.Transparent),
    )
    val bevelShadeBrush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color(0x40000000)),
    )
}
