package au.com.threesixty.cabdispatch.ui.screens.rating

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.TripRatingDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.GlassCard
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * New post-trip "Rate Passenger" screen (2026-09-04) — the ratings backend just landed with no
 * screen to drive it anywhere in this app. Built entirely from the shared HUD kit
 * (`ui/theme/Hud.kt`): a single [GlassCard] over the [CaptainPalette.hudBg] background, matching
 * every other HUD-rebuilt screen in this app. Every visible control here is wired to a real
 * backing call — [RatePassengerViewModel.submit] posts the real `POST /v1/trips/{trip_id}/rating`
 * (see that class's doc); "Skip" is a real, always-available escape hatch that proceeds without
 * ever forcing a rating.
 *
 * Real entry point (see this pass's delivery notes for why "after every close" isn't quite it):
 * [au.com.threesixty.cabdispatch.ui.screens.closepay.CloseAndPayScreen]'s receipt step's
 * "Done — back to For Hire" button now routes here first, via
 * [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes.RATE_PASSENGER], before
 * continuing to the dashboard — see [au.com.threesixty.cabdispatch.ui.navigation.CabDispatchNavHost]'s
 * registration of that route for the exact hand-off.
 */
@Composable
fun RatePassengerScreen(
    onDone: () -> Unit,
    viewModel: RatePassengerViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(CaptainPalette.hudBg), contentAlignment = Alignment.Center) {
        when (val s = state) {
            RatePassengerUiState.Loading -> CircularProgressIndicator(color = CaptainPalette.hudAccent)
            is RatePassengerUiState.NoTrip -> MessageCard(message = s.message, onContinue = onDone)
            is RatePassengerUiState.NotSynced -> NotSyncedCard(message = s.message, onRetry = viewModel::retry, onSkip = onDone)
            is RatePassengerUiState.ReadyToRate -> ReadyToRateCard(state = s, vm = viewModel, onSkip = onDone)
            is RatePassengerUiState.AlreadyRated -> AlreadyRatedCard(rating = s.rating, onContinue = onDone)
            is RatePassengerUiState.Submitted -> SubmittedCard(rating = s.rating, onContinue = onDone)
        }
    }
}

@Composable
private fun MessageCard(message: String, onContinue: () -> Unit) {
    GlassCard(modifier = Modifier.width(480.dp)) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(message, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textSecondary, textAlign = TextAlign.Center)
            CaptainButton(text = "Continue", modifier = Modifier.fillMaxWidth(), onClick = onContinue)
        }
    }
}

@Composable
private fun NotSyncedCard(message: String, onRetry: () -> Unit, onSkip: () -> Unit) {
    GlassCard(modifier = Modifier.width(480.dp)) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Rate this passenger", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            Text(message, fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textSecondary, textAlign = TextAlign.Center)
            CaptainButton(text = "Try again", modifier = Modifier.fillMaxWidth(), onClick = onRetry)
            CaptainButton(text = "Skip", outline = true, modifier = Modifier.fillMaxWidth(), onClick = onSkip)
        }
    }
}

@Composable
private fun ReadyToRateCard(state: RatePassengerUiState.ReadyToRate, vm: RatePassengerViewModel, onSkip: () -> Unit) {
    GlassCard(modifier = Modifier.width(520.dp), glow = CaptainPalette.hudAccent) {
        Column(
            modifier = Modifier.padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("Rate this passenger", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.textPrimary)
            Text(
                "Optional — helps other drivers and the fleet office. Never required to close a trip.",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = CaptainPalette.textMuted,
                textAlign = TextAlign.Center,
            )
            StarRow(stars = state.stars, onStarClick = vm::setStars)
            OutlinedTextField(
                value = state.comment,
                onValueChange = vm::setComment,
                placeholder = { Text("Add a comment (optional)", fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textMuted) },
                textStyle = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textPrimary),
                minLines = 2,
                maxLines = 4,
                enabled = !state.submitting,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CaptainPalette.textPrimary,
                    unfocusedTextColor = CaptainPalette.textPrimary,
                    focusedBorderColor = CaptainPalette.hudAccent,
                    unfocusedBorderColor = CaptainPalette.hudGlassBorderWhite,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = CaptainPalette.hudAccent,
                    focusedPlaceholderColor = CaptainPalette.textMuted,
                    unfocusedPlaceholderColor = CaptainPalette.textMuted,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(it, fontFamily = InterFamily, fontSize = 13.sp, color = CaptainPalette.danger) }
            CaptainButton(
                text = if (state.submitting) "Submitting…" else "Submit rating",
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
                onClick = vm::submit,
            )
            CaptainButton(text = "Skip", outline = true, enabled = !state.submitting, modifier = Modifier.fillMaxWidth(), onClick = onSkip)
        }
    }
}

@Composable
private fun AlreadyRatedCard(rating: TripRatingDto, onContinue: () -> Unit) {
    GlassCard(modifier = Modifier.width(480.dp)) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Already rated", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = CaptainPalette.textPrimary)
            StarRow(stars = rating.stars, onStarClick = null)
            rating.comment?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textSecondary, textAlign = TextAlign.Center)
            }
            CaptainButton(text = "Continue", modifier = Modifier.fillMaxWidth(), onClick = onContinue)
        }
    }
}

@Composable
private fun SubmittedCard(rating: TripRatingDto, onContinue: () -> Unit) {
    GlassCard(modifier = Modifier.width(480.dp), glow = CaptainPalette.success) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Thanks!", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = CaptainPalette.success)
            StarRow(stars = rating.stars, onStarClick = null)
            Text("Rating submitted.", fontFamily = InterFamily, fontSize = 15.sp, color = CaptainPalette.textSecondary)
            CaptainButton(text = "Continue", modifier = Modifier.fillMaxWidth(), onClick = onContinue)
        }
    }
}

/** The 5-star tappable rating control. Read-only (no [onStarClick]) for [AlreadyRatedCard]/
 * [SubmittedCard]; live-tappable for [ReadyToRateCard], where each star sets the rating to its own
 * 1-based position — tapping the currently-set star does not clear it (matches the plain "pick a
 * value" convention every other single-choice control in this app uses, e.g. [CaptainChip] rows). */
@Composable
private fun StarRow(stars: Int, onStarClick: ((Int) -> Unit)?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (position in 1..5) {
            val filled = position <= stars
            Icon(
                imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                contentDescription = "$position star${if (position == 1) "" else "s"}",
                tint = if (filled) CaptainPalette.warning else CaptainPalette.textMuted,
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (onStarClick != null) Modifier.clickable { onStarClick(position) } else Modifier,
                    ),
            )
        }
    }
}
