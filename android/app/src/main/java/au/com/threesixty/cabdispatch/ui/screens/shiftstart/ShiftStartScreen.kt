package au.com.threesixty.cabdispatch.ui.screens.shiftstart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Icon
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
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.ShiftDurationLimit
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * 08 · Shift Start Confirm — reskinned onto the [CaptainPalette] purple design system (2026-08-29
 * pass). Behavior unchanged from the previous version: the shift is ALREADY started by the time
 * this renders ([LoginVehicleBindViewModel.startShift] set [SessionHolder]); this is the
 * confirm/landing bookend. "Start Shift" lands on the dashboard (`IDLE`, popUpTo(0)); Back pops.
 *
 * Layout unchanged from the previous port: centered 700dp confirm card ([CaptainPanel], radius
 * 24) with DRIVER / VEHICLE / REGION / TARIFF / SHIFT LIMIT label-value rows, outline Back +
 * 440×72 primary Start Shift beneath. REGION/TARIFF rows remain the design's own copy (no
 * region/tariff-name state exists on the session — same honesty note as the previous version);
 * DRIVER/VEHICLE/shift-limit are real.
 */
@Composable
fun ShiftStartScreen(navController: NavHostController) {
    val session by SessionHolder.session.collectAsState()
    val s = session

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg)) {
        Column(modifier = Modifier.align(Alignment.Center)) {
            CaptainPanel(modifier = Modifier.width(700.dp), cornerRadiusDp = 24) {
                Column(
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = CaptainPalette.accent, modifier = Modifier.size(30.dp))
                        Text(
                            "Start shift?",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                            color = CaptainPalette.textPrimary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    ConfirmRow("DRIVER", if (s != null) "${s.driverName} · ${s.driverId.take(8)}" else "—")
                    ConfirmRow("VEHICLE", s?.vehicleId?.let { "Vehicle $it" } ?: "—")
                    ConfirmRow("REGION", "Urban (auto-detected via GPS)")
                    ConfirmRow("TARIFF", "Lilly Cabs urban rank/hail · Ed25519 signed ✓", valueColor = CaptainPalette.success)
                    ConfirmRow(
                        "SHIFT LIMIT",
                        "${ShiftDurationLimit.SHIFT_DURATION_LIMIT_HOURS.toInt()}h 00m — countdown shows on the status strip",
                    )
                }
            }
            Spacer(Modifier.padding(top = 28.dp))
            Row(modifier = Modifier.width(700.dp)) {
                CaptainButton(text = "Back", outline = true, modifier = Modifier.width(220.dp)) {
                    navController.popBackStack()
                }
                Spacer(Modifier.weight(1f))
                CaptainButton(
                    text = "Start Shift",
                    heightDp = 72,
                    fontSize = 22.sp,
                    modifier = Modifier.width(440.dp),
                ) {
                    navController.navigate(CabDispatchRoutes.IDLE) { popUpTo(0) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = CaptainPalette.textPrimary,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = CaptainPalette.textMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(value, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = valueColor)
    }
}
