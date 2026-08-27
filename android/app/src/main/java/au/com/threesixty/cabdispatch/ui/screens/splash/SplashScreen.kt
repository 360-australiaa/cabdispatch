package au.com.threesixty.cabdispatch.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.BuildConfig
import au.com.threesixty.cabdispatch.domain.TermsAcceptance
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.navigation.postAuthDestination
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily
import kotlinx.coroutines.delay

/**
 * 01 · Splash — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `7:73`). Visual layer
 * only: the dwell timer, terms/session gate, and navigation are unchanged from the previous
 * versions of this file (see [postAuthDestination] and [TermsAcceptance] for the branch logic —
 * [au.com.threesixty.cabdispatch.domain.SessionHolder] is still in-memory-only, so a cold start
 * always lands on sign-in; that pre-existing TODO is unrelated to this reskin).
 *
 * v2 frame contents (all real): 120dp yellow CD tile (radius 30) · Inter Bold 40 wordmark ·
 * 18sp subtitle · 360×6 progress track with an animated yellow bar (the frame draws the bar at
 * 220/360 — here it sweeps, since a static 61% bar on a live screen would read as "stuck") ·
 * loading line · Roboto Mono footer "v0.1.0 · TSP-448041 · Ed25519 tariff verification".
 */
@Composable
fun SplashScreen(navController: NavHostController) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_DWELL_MS)
        val termsAccepted = TermsAcceptance.isAccepted(context, BuildConfig.VERSION_CODE)
        val destination = if (termsAccepted) postAuthDestination() else CabDispatchRoutes.TERMS_DISCLAIMER
        navController.navigate(destination) {
            popUpTo(CabDispatchRoutes.SPLASH) { inclusive = true }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Deck.yellow),
                contentAlignment = Alignment.Center,
            ) {
                Text("CD", color = Deck.onYellow, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp)
            }
            Text("CAB DISPATCH", color = Deck.textPrimary, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp)
            Text("The Captain Taxis · NSW Taxi Meter", color = Deck.textSecondary, fontFamily = InterFamily, fontSize = 18.sp)
            LoadingBar()
            Text("Loading tariffs & signing keys…", color = Deck.textMuted, fontFamily = InterFamily, fontSize = 15.sp)
        }
        Text(
            "v${BuildConfig.VERSION_NAME} · TSP-448041 · Ed25519 tariff verification",
            color = Deck.textMuted,
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp),
        )
    }
}

/** Figma 7:79/7:80 — 360×6 track (card tone, radius 3) with a 220dp yellow bar, animated. */
@Composable
private fun LoadingBar() {
    val sweep by rememberInfiniteTransition(label = "splash-bar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "splash-bar-x",
    )
    Box(
        modifier = Modifier
            .width(360.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Deck.card),
    ) {
        Box(
            modifier = Modifier
                .padding(start = (sweep * 220).dp)
                .width(140.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Deck.yellow),
        )
    }
}

private const val SPLASH_MIN_DWELL_MS = 900L
