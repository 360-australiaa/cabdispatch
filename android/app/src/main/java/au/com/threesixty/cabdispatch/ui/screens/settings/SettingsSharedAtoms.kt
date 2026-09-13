package au.com.threesixty.cabdispatch.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.HudStatusPill
import au.com.threesixty.cabdispatch.ui.theme.HudTone
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.color

// W7 file split (2026-09-13): extracted from the former monolithic SettingsScreen.kt (1,437
// lines) — see that file's own doc for the split rationale. This file is the small building
// blocks more than one tab-content file uses ([SectionLabel] alone is read by five of them).
// Everything here is `internal` (not `private`) for exactly that reason — every declaration below
// is called across a file boundary from at least one tab-content file — same package
// (`ui.screens.settings`), so no import needed either way, only the visibility modifier changes
// from the pre-split file.

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        color = CaptainPalette.textMuted,
    )
}

@Composable
internal fun ComingSoonBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CaptainPalette.hudTrack)
            .border(1.dp, CaptainPalette.hudGlassBorderPurple, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "COMING SOON",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            color = CaptainPalette.textMuted,
        )
    }
}

@Composable
internal fun ComingSoonTabContent(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(36.dp))
        Text(
            title,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = CaptainPalette.textPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            message,
            fontFamily = InterFamily,
            fontSize = 15.sp,
            color = CaptainPalette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(16.dp))
        ComingSoonBadge()
    }
}

/** A real, working preference row — [checked]/[onCheckedChange] both back a genuine
 * [au.com.threesixty.cabdispatch.domain.SettingsPreferencesStore] flag (Auto Accept Jobs/Show Map
 * in Background/Allow Cash), never decorative. Same [androidx.compose.material3.Switch] color
 * treatment `FareScheduleContent`'s pre-existing maxi-vehicle switch already used, for visual
 * consistency across every real toggle in this screen. HUD kit rebuild: the row's surface is now a
 * [GlassCard] instead of a flat bordered [Row] — the toggle's [checked]/[onCheckedChange] wiring is
 * untouched. */
@Composable
internal fun ToggleSettingRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
                Text(
                    description,
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = CaptainPalette.textMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = CaptainPalette.primary,
                    checkedThumbColor = CaptainPalette.hudAccent,
                ),
            )
        }
    }
}

/**
 * A decorative, deliberately-locked row (Language/Units) — per this plan's confirmed
 * decision, shown for visual completeness but never fake-functional: greyed out (55% alpha), a
 * lock glyph instead of a working control, and a "COMING SOON" badge instead of a value that looks
 * editable. Tapping it is safe (never a crash, never a silent no-op that looks like it worked) — a
 * brief [android.widget.Toast] names the row and says it isn't available yet, then disappears on
 * its own; no new dialog/snackbar plumbing needed for a row this deliberately inert. HUD kit
 * rebuild: same [GlassCard] surface as [ToggleSettingRow], still no glow (a locked row shouldn't
 * read as "live" the way a real toggle does).
 */
@Composable
internal fun LockedSettingRow(label: String, value: String) {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth().alpha(0.55f), cornerRadiusDp = 16) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    android.widget.Toast.makeText(context, "$label isn't available yet", android.widget.Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = CaptainPalette.textMuted, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = CaptainPalette.textPrimary)
                Text(value, fontFamily = InterFamily, fontSize = 14.sp, color = CaptainPalette.textMuted, modifier = Modifier.padding(top = 2.dp))
            }
            ComingSoonBadge()
        }
    }
}

// --- Diagnostics tiles ---

internal enum class DiagTone { OK, WARN, BAD }

/** Maps this screen's pre-existing OK/WARN/BAD diagnostic tone onto the kit's [HudTone] so
 * [DiagTile] can render its status through [HudStatusPill] — same three-way meaning, just the kit's
 * vocabulary (BAD -> Danger is the one non-obvious mapping, since this screen never uses
 * [HudTone.Accent] for a diagnostic). */
internal fun DiagTone.toHudTone(): HudTone = when (this) {
    DiagTone.OK -> HudTone.Success
    DiagTone.WARN -> HudTone.Warning
    DiagTone.BAD -> HudTone.Danger
}

/** One diagnostics tile: icon · name, then the real status read through a [HudStatusPill]
 * ([tone] mapped via [DiagTone.toHudTone]) instead of the old colored-dot-in-a-panel — the kit's
 * "GlassCard surface, HudStatusPill status" convention. [glow] halos the whole card when
 * non-nominal, same "amber/red border on trouble" signal the old tile gave, just as a kit glow
 * instead of a border. */
@Composable
internal fun DiagTile(
    icon: ImageVector,
    name: String,
    sub: String,
    tone: DiagTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val hudTone = tone.toHudTone()
    GlassCard(
        modifier = modifier
            .height(128.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        cornerRadiusDp = 16,
        glow = if (tone == DiagTone.OK) null else hudTone.color(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CaptainPalette.hudAccent, modifier = Modifier.size(20.dp))
                Text(
                    name,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = CaptainPalette.textPrimary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            HudStatusPill(label = "Status", value = sub, tone = hudTone, pulsing = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Action tile: icon above a bold label, elderly-friendly button sizing; danger-tinted for the
 * factory reset via the same [GlassCard] `glow` the rest of the kit uses for a non-neutral surface,
 * instead of the old flat danger border. */
@Composable
internal fun ActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (danger) CaptainPalette.danger else CaptainPalette.hudAccent
    GlassCard(
        modifier = modifier
            .height(96.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        cornerRadiusDp = 16,
        glow = if (danger) CaptainPalette.danger else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = if (danger) CaptainPalette.danger else CaptainPalette.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
