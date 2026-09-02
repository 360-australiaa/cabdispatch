package au.com.threesixty.cabdispatch.ui.screens.shiftsubmitted

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ShiftSubmissionHandoff
import au.com.threesixty.cabdispatch.domain.TripDetailHandoff
import au.com.threesixty.cabdispatch.domain.format.asMoney
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.RoundingMode

/**
 * 29 · Shift Submitted — reskinned onto the [CaptainPalette] purple design system (2026-08-29
 * pass). Same data source/navigation as every previous version: totals come from
 * [ShiftSubmissionHandoff.pending] (captured at submit time), and the primary CTA clears the
 * handoffs + [SessionHolder] and pops to S1 with an empty back stack.
 *
 * Centered full-takeover layout, unchanged from the previous port: 140dp success-tinted check
 * ring, 40sp "Shift submitted", a summary line rendered from REAL handoff values (trips ·
 * reconciled total · PSL levies), static "Report available on the fleet dashboard" line, and a
 * 360×72 primary "Done — Log Off" CTA. One addition kept from before the port: a small Roboto
 * Mono detail line with the cash/card split, km, and hours the handoff also carries — real data
 * this screen showed before, kept rather than silently dropped.
 */
@Composable
fun ShiftSubmittedScreen(navController: NavHostController) {
    val summary by ShiftSubmissionHandoff.pending.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(CaptainPalette.success.copy(alpha = 0.14f), CircleShape)
                .border(4.dp, CaptainPalette.success, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = CaptainPalette.success, modifier = Modifier.size(72.dp))
        }

        Text("Shift submitted", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = CaptainPalette.textPrimary)

        val s = summary
        if (s == null) {
            Text(
                "No shift summary available.",
                fontFamily = InterFamily,
                fontSize = 18.sp,
                color = CaptainPalette.textSecondary,
            )
        } else {
            Text(
                "${s.tripsCount} trips · ${(s.cashTotal + s.cardTotal).asMoney()} reconciled · " +
                    "${s.pslAccrued.asMoney()} levies posted to the PSL ledger\n" +
                    "Report available on the fleet dashboard",
                fontFamily = InterFamily,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = CaptainPalette.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                "Cash ${s.cashTotal.asMoney()} · Card ${s.cardTotal.asMoney()} · " +
                    "${s.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString()} km · " +
                    "${"%.1f".format(s.hoursOnShift)} h on shift",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
            )
        }

        CaptainButton(
            text = "Done — Log Off",
            heightDp = 72,
            modifier = Modifier.width(360.dp),
        ) {
            finishAndReturnToLogin(navController)
        }
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
