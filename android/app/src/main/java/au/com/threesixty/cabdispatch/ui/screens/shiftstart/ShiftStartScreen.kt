package au.com.threesixty.cabdispatch.ui.screens.shiftstart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Row 5 — Shift start confirmation (spec §8: "Not yet designed"). A simple "You're on shift"
 * confirmation shown once, right after S1's pre-shift-inspection step successfully starts the
 * shift ([LoginVehicleBindViewModel.startShift] having already called [SessionHolder.set]).
 *
 * Deliberately mirrors [au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen]'s
 * "receipt-card" visual pattern — big circular glyph, heading, summary line, single full-width
 * gold action button — per this agent's brief ("matching the submit-shift-confirmation visual
 * pattern a sibling agent is building, for symmetry"): shift-start and shift-submitted are the two
 * bookends of a driver's working day and should read as the same design family. Gold (not green)
 * for the circle here specifically, since this is a *starting* state, not a completed/paid one —
 * green stays reserved for "done/settled" per [WheelColors.available]'s usage everywhere else.
 *
 * Reads [SessionHolder.session] directly rather than a nav-arg/hand-off object — by the time this
 * screen is reached the session is already set, same "read the already-set singleton" pattern
 * used throughout this flow (see [au.com.threesixty.cabdispatch.domain.Session]'s doc).
 *
 * Verified (reconciliation pass): CONTINUE targets [CabDispatchRoutes.IDLE], which is registered
 * in `CabDispatchNavHost.kt` to render [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * (the wheel-dashboard shell) under that same route key — no repoint needed, this already lands on
 * the real dashboard.
 */
@Composable
fun ShiftStartScreen(navController: NavHostController) {
    val session by SessionHolder.session.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Row(
            modifier = Modifier
                .size(64.dp)
                .background(WheelColors.gold, CircleShape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🚖", fontSize = 28.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("You're on shift", color = WheelColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        val s = session
        if (s == null) {
            Spacer(Modifier.height(8.dp))
            Text("Shift started.", color = WheelColors.textSecondary, fontSize = 13.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "${s.driverName} · Vehicle ${s.vehicleId}",
                color = WheelColors.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text("Drive safe.", color = WheelColors.gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Toggle Available on the dashboard when you're ready for a hire.",
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            "CONTINUE",
            color = IndigoOnGold,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(min = 220.dp)
                .background(WheelColors.gold, RoundedCornerShape(14.dp))
                .clickable {
                    navController.navigate(CabDispatchRoutes.IDLE) {
                        popUpTo(0)
                    }
                }
                .padding(horizontal = 32.dp, vertical = 16.dp),
        )
    }
}

/** #2A1C58 — text color on top of a gold surface, same coincidental-not-semantic reuse
 * [au.com.threesixty.cabdispatch.ui.screens.messages.MessageThreadScreen]'s
 * `CabDispatchIndigoText` and [au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted.ShiftSubmittedScreen]
 * document; kept as its own local constant per that established per-file convention. */
private val IndigoOnGold = Color(0xFF2A1C58)
