package au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors
import java.math.RoundingMode

/**
 * Row 19 — Submit shift confirmation (spec §8: "not designed" in the HTML
 * prototype — matched to the *payment receipt* screen's visual pattern per
 * this agent's brief, i.e. `driver-dashboard-full-prototype.html`'s
 * `.receipt-card`: green checkmark circle, heading, total-style summary,
 * DONE button). Reads the totals [ShiftWheelContent][au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent]
 * captured at submit time via [ShiftSubmissionHandoff] — see that class's doc
 * for why (avoids re-running or double-instantiating
 * [au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftReportViewModel]).
 *
 * DONE mirrors the payment receipt screen's DONE (spec §7.4: "resets the
 * whole session") — a submitted shift ends the driver's working day, so this
 * clears [SessionHolder] and returns to S1 (Login/vehicle bind), same
 * destination the existing (pre-wheel) `ShiftReportScreen`'s onDone already
 * used.
 *
 * Verified (reconciliation pass): reached exactly this way — the Shift wheel-slot content
 * ([au.com.threesixty.cabdispatch.ui.screens.shiftreport.ShiftWheelContent], hosted by
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]) navigates here via
 * [androidx.navigation.NavHostController.navigate] once Submit Shift succeeds.
 */
@Composable
fun ShiftSubmittedScreen(navController: NavHostController) {
    val summary by ShiftSubmissionHandoff.pending.collectAsState()

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
                .background(WheelColors.available, CircleShape),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓", color = WheelColors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text("Shift submitted", color = WheelColors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        val s = summary
        if (s == null) {
            Spacer(Modifier.height(8.dp))
            Text("No shift summary available.", color = WheelColors.textSecondary, fontSize = 13.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "${s.tripsCount} trips · ${"%.1f".format(s.hoursOnShift)} hours on shift",
                color = WheelColors.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                (s.cashTotal + s.cardTotal).asMoney(),
                color = WheelColors.gold,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Cash to reconcile ${s.cashTotal.asMoney()} · Card settled ${s.cardTotal.asMoney()} · " +
                    "PSL accrued ${s.pslAccrued.asMoney()} · ${s.kmTotal.setScale(1, RoundingMode.HALF_UP)} km",
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            "DONE",
            color = WheelColors.surfaceRaised,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(min = 220.dp)
                .background(WheelColors.gold, RoundedCornerShape(14.dp))
                .clickable { finishAndReturnToLogin(navController) }
                .padding(horizontal = 32.dp, vertical = 16.dp),
        )
    }
}

private fun finishAndReturnToLogin(navController: NavHostController) {
    ShiftSubmissionHandoff.clear()
    TripDetailHandoff.clear()
    SessionHolder.clear()
    navController.navigate(CabDispatchRoutes.LOGIN_VEHICLE_BIND) {
        popUpTo(0)
    }
}
