package au.com.threesixty.cabdispatch.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import kotlinx.coroutines.delay

/**
 * Row 1 — Splash (spec §8, "System": "Not yet designed" — no Figma/HTML reference exists;
 * designed inline here matching the [WheelColors] token system). Brand mark + a brief loading
 * state, then routes to S1 (Login/vehicle bind) or straight past it to S2 (dashboard) depending
 * on whether a driver session already exists.
 *
 * **Honest limitation, worth flagging loudly rather than silently under-delivering:**
 * [SessionHolder] is explicitly documented as an **in-memory-only** holder — see its own doc
 * comment, which already flags "fold this into a proper SessionRepository backed by Room/DataStore
 * ... so a process restart doesn't silently drop the driver back to S1 mid-shift" as a TODO for
 * the integration agent. That TODO is still open. So the check below only skips S1 across an
 * Activity recreation / backgrounding where the process itself survives (e.g. rotation, or the
 * OS reclaiming just the Activity) — on a genuine cold start (process death, device reboot, first
 * launch) [SessionHolder.session] is always null and this always routes to S1. That's the safe
 * default (never silently resume a shift with no driver present to confirm it), just not yet the
 * full "leave it on the dash overnight, come back and you're still signed in" experience the
 * spec's row name implies. Once a persisted session lands, swap the `SessionHolder.session.value`
 * read below for that repository's synchronous has-valid-session check — the splash shape (brief
 * dwell -> branch to S1 or S2) doesn't need to change.
 */
@Composable
fun SplashScreen(navController: NavHostController) {
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_DWELL_MS)
        val destination = if (SessionHolder.session.value != null) {
            CabDispatchRoutes.IDLE
        } else {
            CabDispatchRoutes.LOGIN_VEHICLE_BIND
        }
        navController.navigate(destination) {
            popUpTo(CabDispatchRoutes.SPLASH) { inclusive = true }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand mark deliberately echoes the dashboard's rotating-wheel metaphor (spec §1) —
        // a gold ring around a steering-wheel glyph — rather than a generic logo mark.
        Box(
            modifier = Modifier
                .size(96.dp)
                .border(3.dp, WheelColors.gold, CircleShape)
                .background(WheelColors.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("🚕", fontSize = 40.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "CAB DISPATCH",
            color = WheelColors.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Captain Taxis driver terminal",
            color = WheelColors.textSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = WheelColors.gold,
            strokeWidth = 3.dp,
        )
    }
}

private const val SPLASH_MIN_DWELL_MS = 900L
