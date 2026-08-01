package au.com.threesixty.cabdispatch.ui.overlays

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import java.util.Locale

/**
 * Row 28 — "Navigate" contextual overlay (spec §8 / §7 designer note: "explicitly NOT custom
 * turn-by-turn ... a simple button/overlay that deep-links to the device's default maps app ...
 * nothing more elaborate"). A place ([lat]/[lng]) plus a human-readable [label] — the caller
 * builds this from whatever it already has: a [au.com.threesixty.cabdispatch.data.remote.JobDto]'s
 * `originLat/originLng/originAddress` (navigate to an accepted job's pickup) or
 * `destLat/destLng/destAddress` (navigate to its drop-off) are the obvious sources once the
 * Available-Trips wheel-content screen lands.
 *
 * Resolved (reconciliation pass): the Available-Trips job-offer detail screen
 * ([au.com.threesixty.cabdispatch.ui.screens.availabletrips.AvailableTripOfferScreen]) is the
 * call site that landed — it took the second of this doc's two suggested options, calling
 * [openInMaps] directly from its own "📍 Navigate to pickup" text/button rather than composing
 * this [NavigateOverlay] card. That means [NavigateOverlay] itself currently has no call site in
 * the app (only [openInMaps]/[NavigationTarget] are used) — left in place as a ready-made overlay
 * for any future call site that wants the fuller card treatment (e.g. mid-trip navigate-to-pickup
 * once accepting a job no longer jumps straight to S3/Hired), not dead code to delete.
 */
data class NavigationTarget(val lat: Double, val lng: Double, val label: String)

/**
 * `Intent.ACTION_VIEW` + `geo:` URI deep link into the device's default maps app — deliberately
 * not a custom turn-by-turn implementation (see [NavigateOverlay]'s doc). `geo:0,0?q=lat,lng(label)`
 * is the documented cross-app form that works whether or not a query is also present; falls back
 * to a `https://maps.google.com/...` web URL if no `geo:`-handling app is installed (some AOSP/
 * tablet builds ship no default Maps app at all), and swallows the case where *neither* resolves
 * rather than crashing the meter over what's explicitly a "nothing more elaborate" convenience
 * feature.
 */
fun openInMaps(context: Context, target: NavigationTarget) {
    val encodedLabel = Uri.encode(target.label)
    val geoUri = Uri.parse(
        String.format(Locale.US, "geo:0,0?q=%f,%f(%s)", target.lat, target.lng, encodedLabel),
    )
    val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
    try {
        context.startActivity(geoIntent)
        return
    } catch (_: ActivityNotFoundException) {
        // fall through to the web fallback below
    }

    val webUri = Uri.parse(
        String.format(Locale.US, "https://maps.google.com/?q=%f,%f", target.lat, target.lng),
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    } catch (_: ActivityNotFoundException) {
        // No app on-device can handle either form — nothing sensible left to do short of a
        // custom map renderer, which is explicitly out of scope (see this file's header doc).
    }
}

/**
 * Bottom-anchored card: destination address + a single primary "Open in Maps" action (deep-links
 * out via [openInMaps]) + a dismiss affordance. Intentionally not a full-screen takeover — unlike
 * the duress overlays (see [DuressTriggeredOverlay]), navigation is a convenience the driver can
 * ignore/dismiss at any time, never a safety-critical interrupt.
 */
@Composable
fun NavigateOverlay(target: NavigationTarget, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(WheelColors.surfaceRaised, RoundedCornerShape(16.dp))
            .border(1.dp, WheelColors.borderStrong, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("NAVIGATE", color = WheelColors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(target.label, color = WheelColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "✕",
                color = WheelColors.textSecondary,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .background(WheelColors.gold, RoundedCornerShape(12.dp))
                .clickable { openInMaps(context, target) }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("OPEN IN MAPS", color = WheelColors.surfaceRaised, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}
