package au.com.threesixty.cabdispatch.ui.screens.terms

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.domain.TermsAcceptance
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * Boot-time Terms & Conditions / Privacy Policy disclaimer (2026-08-10 meter-polish pass,
 * matching a real competitor taxi-meter app's own boot-time disclaimer screen). Registered ahead
 * of S1 (Login/Vehicle bind) in [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost] --
 * see [au.com.threesixty.cabdispatch.ui.screens.splash.SplashScreen]'s doc for the gate that
 * decides whether this screen is shown at all. Gated per app-version, not per-install -- see
 * [TermsAcceptance]'s own doc for why.
 *
 * [onAccept] records acceptance (via [TermsAcceptance.markAccepted]) and continues to wherever
 * Splash would otherwise have sent the driver (S1 or S2). Declining -- genuinely untested, since
 * this whole module has never run on a real device (see HANDOFF.md's standing caveat) -- finishes
 * the hosting [ComponentActivity] outright: this app has no meaningful "use it without accepting"
 * state to fall back to (every screen past this one assumes a session/tariff/etc the driver
 * cannot reach without accepting), so declining exits rather than looping back to this same
 * screen or silently letting the driver in anyway.
 */
@Composable
fun TermsDisclaimerScreen(onAccept: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg)
            .padding(24.dp),
    ) {
        Text(
            "Terms & Conditions",
            color = WheelColors.textPrimary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 22.sp,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // Reference-screenshot tone, matched per the task brief -- not real legal text (no
            // legal-reviewed Terms & Conditions/Privacy Policy document exists in this repo to
            // source from; see HANDOFF.md for this honestly-flagged gap).
            Text(
                "Please read and accept our Terms and Conditions and Privacy Policy before " +
                    "continuing. Your continued use of this app will mean you have accepted " +
                    "these terms.",
                color = WheelColors.textSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "This app records trip fares, GPS location while a trip is in progress, and " +
                    "shift and compliance data required under the NSW Point to Point Transport " +
                    "regulations. Data is transmitted to Cab Dispatch servers when the device " +
                    "has connectivity and is otherwise queued securely on-device.",
                color = WheelColors.textMuted,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { (context as? ComponentActivity)?.finishAffinity() },
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }

            Button(
                onClick = {
                    TermsAcceptance.markAccepted(context, BuildConfig.VERSION_CODE)
                    onAccept()
                },
                colors = ButtonDefaults.buttonColors(containerColor = WheelColors.gold),
                modifier = Modifier.weight(1f),
            ) { Text("Accept", color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
}
