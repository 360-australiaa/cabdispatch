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
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import java.math.RoundingMode

/**
 * 29 · Shift Submitted — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `27:81`).
 * Same data source/navigation as every previous version: totals come from
 * [ShiftSubmissionHandoff.pending] (captured at submit time), and the primary CTA clears the
 * handoffs + [SessionHolder] and pops to S1 with an empty back stack.
 *
 * Centered full-takeover layout per the frame: 140dp green ✓ ring, 40sp "Shift submitted", the
 * frame's summary line rendered from REAL handoff values (trips · reconciled total · PSL levies),
 * static "Report available on the fleet dashboard" line, and a 360×72 yellow "Done — Log Off"
 * primary. One addition beyond the frame: a small Roboto Mono detail line with the cash/card
 * split, km, and hours the handoff also carries — real data this screen showed before the port,
 * kept rather than silently dropped.
 */
@Composable
fun ShiftSubmittedScreen(navController: NavHostController) {
    val summary by ShiftSubmissionHandoff.pending.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deck.canvas),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(Deck.forHire.copy(alpha = 0.14f), CircleShape)
                .border(4.dp, Deck.forHire, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 64.sp, color = Deck.forHire)
        }

        Text("Shift submitted", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Deck.textPrimary)

        val s = summary
        if (s == null) {
            Text(
                "No shift summary available.",
                fontFamily = InterFamily,
                fontSize = 18.sp,
                color = Deck.textSecondary,
            )
        } else {
            Text(
                "${s.tripsCount} trips · ${(s.cashTotal + s.cardTotal).asMoney()} reconciled · " +
                    "${s.pslAccrued.asMoney()} levies posted to the PSL ledger\n" +
                    "Report available on the fleet dashboard",
                fontFamily = InterFamily,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = Deck.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                "Cash ${s.cashTotal.asMoney()} · Card ${s.cardTotal.asMoney()} · " +
                    "${s.kmTotal.setScale(1, RoundingMode.HALF_UP).toPlainString()} km · " +
                    "${"%.1f".format(s.hoursOnShift)} h on shift",
                fontFamily = RobotoMonoFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = Deck.textMuted,
            )
        }

        DeckButton(
            text = "Done — Log Off",
            kind = DeckButtonKind.Primary,
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
