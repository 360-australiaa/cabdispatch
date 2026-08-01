package au.com.threesixty.cabdispatch.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * S14 — Message detail/quick-reply, spec TCT-DRIVER-APP-01.md §8 row 13-14 ("Messages: ... message
 * detail/quick-reply" — flagged **not yet designed**, designed inline here per the task brief).
 * No Figma/HTML reference exists for this screen; it deliberately reuses the same [WheelColors]
 * token system and `.list-row`-adjacent visual language as [MessagesWheelContent] (the one
 * reference the list side of this feature does have) rather than inventing a new look.
 *
 * One thread per driver (see [MessagesViewModel] doc), so this is a single always-open thread
 * view + a bottom quick-reply composer — not a multi-thread inbox.
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]'s
 * Messages wheel-slot content ([MessagesWheelContent]) `onOpenThread` affordance targets this same
 * route ([CabDispatchRoutes.MESSAGES_THREAD]) — see that screen's `MessagesSlotContent`.
 */
@Composable
fun MessageThreadScreen(
    navController: NavHostController,
    viewModel: MessagesViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.markUnreadAsRead() }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WheelColors.bg),
    ) {
        ThreadTopBar(navController)

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> CenteredThreadMessage("Loading messages…")
                state.messages.isEmpty() -> CenteredThreadMessage("No messages yet — dispatch will reach you here.")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                }
            }
        }

        if (state.error != null) {
            Text(
                state.error,
                color = WheelColors.duress,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.sendError != null) {
            Text(
                state.sendError,
                color = WheelColors.duress,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        ReplyComposer(
            text = state.composerText,
            sending = state.sending,
            onTextChange = viewModel::updateComposerText,
            onSend = viewModel::sendReply,
        )
    }
}

@Composable
private fun ThreadTopBar(navController: NavHostController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = WheelColors.textPrimary,
                fontSize = 24.sp,
                modifier = Modifier
                    .clickable { navController.popBackStack() }
                    .padding(end = 12.dp),
            )
            Text(
                "Messages",
                color = WheelColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        // S6 (settings) reachable from anywhere, per spec.
        Text(
            "⚙",
            color = WheelColors.textSecondary,
            fontSize = 18.sp,
            modifier = Modifier
                .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                .padding(4.dp),
        )
    }
}

@Composable
private fun CenteredThreadMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = WheelColors.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessageBubble(message: MessageDto) {
    val fromDriver = message.senderType != "dispatch"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromDriver) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (fromDriver) Alignment.End else Alignment.Start,
        ) {
            if (!fromDriver) {
                Text(
                    "Dispatch",
                    color = WheelColors.textMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (fromDriver) WheelColors.gold else WheelColors.surfaceRaised,
                        shape = RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (fromDriver) 14.dp else 4.dp,
                            bottomEnd = if (fromDriver) 4.dp else 14.dp,
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.body,
                    color = if (fromDriver) CabDispatchIndigoText else WheelColors.textPrimary,
                    fontSize = 14.sp,
                )
            }
            Text(
                text = formatMessageClockTime(message.sentAt),
                color = WheelColors.textMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/** #2A1C58 — matches `.pay-btn-primary{ color:#2A1C58 }` in the reference prototype: text color
 * on top of a gold bubble/button, kept as its own constant rather than reusing [WheelColors.surfaceRaised]
 * since the coincidence (same hex) is about the prototype's specific gold-button text color choice,
 * not a semantic link to the "raised surface" token. */
private val CabDispatchIndigoText = Color(0xFF2A1C58)

@Composable
private fun ReplyComposer(
    text: String,
    sending: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WheelColors.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Reply to dispatch…", color = WheelColors.textMuted) },
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = WheelColors.surfaceRaised,
                unfocusedContainerColor = WheelColors.surfaceRaised,
                disabledContainerColor = WheelColors.surfaceRaised,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = WheelColors.textPrimary,
                unfocusedTextColor = WheelColors.textPrimary,
                cursorColor = WheelColors.gold,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onSend,
            enabled = !sending && text.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = WheelColors.gold,
                contentColor = CabDispatchIndigoText,
                disabledContainerColor = WheelColors.surfaceRaised,
                disabledContentColor = WheelColors.textMuted,
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    color = CabDispatchIndigoText,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("SEND", fontWeight = FontWeight.Bold)
            }
        }
    }
}
