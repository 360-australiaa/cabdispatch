package au.com.threesixty.cabdispatch.ui.screens.terms

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.domain.SessionHolder
import au.com.threesixty.cabdispatch.domain.TermsAcceptance
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

private const val DISCLAIMER_PARA_1 =
    "Cab Dispatch does not warrant that any information provided by the product is accurate or " +
        "complete. It is your responsibility to observe safe driving practices. Do not operate " +
        "this product while driving and only use it when safe to do so. You should use this " +
        "product as a driving aid only and use your own judgement while driving, taking into " +
        "account prevailing road conditions and the characteristics of the vehicle you are driving."

private const val DISCLAIMER_PARA_2 =
    "To the extent permitted by law, you accept full responsibility for any loss, liability or " +
        "damage arising out of, or in connection with, the use of this product. Your continued " +
        "use of this product will mean that you have accepted these terms."

/**
 * 02 · Terms & Disclaimer — reskinned onto the [CaptainPalette] purple design system (2026-08-29
 * pass). Behavior unchanged from the previous version: [onAccept] records acceptance via
 * [TermsAcceptance.markAccepted] (gated per app-version) and continues to wherever Splash would
 * have sent the driver; Cancel finishes the hosting [ComponentActivity] — there is no meaningful
 * "use it without accepting" state.
 *
 * Layout unchanged from the previous port: left column (H1 "Disclaimer" · subtitle · mono context
 * chip) + right terms card (panel surface, 600dp text column), Cancel outline bottom-left, 360×72
 * primary Accept bottom-right. Zero scroll — the body copy fits the card at 17sp/1.52 line height.
 * The context chip shows the real session ("Driver GL2HY · Vehicle S5517") when one exists; at
 * boot time there is usually no session yet, so this renders the app version stamp instead —
 * never fabricated IDs.
 */
@Composable
fun TermsDisclaimerScreen(onAccept: () -> Unit) {
    val context = LocalContext.current
    val session by SessionHolder.session.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(CaptainPalette.bg)
            .padding(start = 72.dp, end = 72.dp, top = 96.dp, bottom = 44.dp),
    ) {
        // Left column — headline, subtitle, context chip, Cancel pinned to the bottom.
        Column(modifier = Modifier.width(392.dp)) {
            Text("Disclaimer", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = CaptainPalette.textPrimary)
            Spacer(Modifier.weight(0.06f))
            Text(
                "Please read and accept the disclaimer to continue. Accepting is recorded against your driver ID and this vehicle.",
                fontFamily = InterFamily,
                fontSize = 17.sp,
                color = CaptainPalette.textSecondary,
                modifier = Modifier.width(380.dp),
            )
            Spacer(Modifier.padding(top = 16.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CaptainPalette.raised)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                val chipText = session?.let { "Driver ${it.driverName} · Vehicle ${it.vehicleId}" }
                    ?: "v${BuildConfig.VERSION_NAME} · first run on this tablet"
                Text(chipText, fontFamily = RobotoMonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = CaptainPalette.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            CaptainButton(
                text = "Cancel",
                outline = true,
                modifier = Modifier.width(220.dp),
                heightDp = 64,
            ) {
                (context as? ComponentActivity)?.finishAffinity()
            }
        }
        Spacer(Modifier.width(72.dp))
        // Right column — the terms card, Accept pinned bottom-right beneath it.
        Column(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 36.dp, vertical = 32.dp),
            ) {
                Text(
                    DISCLAIMER_PARA_1,
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    color = CaptainPalette.textSecondary,
                )
                Spacer(Modifier.padding(top = 18.dp))
                Text(
                    DISCLAIMER_PARA_2,
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    color = CaptainPalette.textSecondary,
                )
            }
            Spacer(Modifier.weight(1f))
            Row {
                Spacer(Modifier.weight(1f))
                CaptainButton(
                    text = "Accept",
                    heightDp = 72,
                    modifier = Modifier.width(360.dp),
                ) {
                    TermsAcceptance.markAccepted(context, BuildConfig.VERSION_CODE)
                    onAccept()
                }
            }
        }
    }
}

/** Kept for callers/tests referencing the copy; also documents that this is real product copy. */
object TermsCopy {
    const val paragraph1 = DISCLAIMER_PARA_1
    const val paragraph2 = DISCLAIMER_PARA_2
}
