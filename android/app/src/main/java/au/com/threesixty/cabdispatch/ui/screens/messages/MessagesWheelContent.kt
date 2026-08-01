package au.com.threesixty.cabdispatch.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.ui.theme.WheelColors

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.MESSAGES] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Messages: list of dispatch messages — sender, preview text,
 * timestamp") — a direct Compose port of the reference prototype's `row(...)`-generated
 * `.list-row` markup for the Messages slot (docs/driver-dashboard-full-prototype.html lines
 * ~332-351), newest message first.
 *
 * This composable renders only the content-pane *body* (the prototype's `#detailBody` — the list
 * itself), not the pane's `eyebrow`/`hero` title chrome, which the wheel-dashboard host screen
 * owns for every slot uniformly (see spec §4's "Left content pane" intro).
 *
 * Verified (reconciliation pass): [au.com.threesixty.cabdispatch.ui.screens.dashboard.WheelDashboardScreen]
 * renders this composable for [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.MESSAGES], wiring
 * [onOpenThread] exactly as suggested below — see that screen's `MessagesSlotContent`:
 * `MessagesWheelContent(onOpenThread = { navController.navigate(CabDispatchRoutes.MESSAGES_THREAD) })`.
 */
@Composable
fun MessagesWheelContent(
    modifier: Modifier = Modifier,
    onOpenThread: () -> Unit = {},
    viewModel: MessagesViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        when {
            state.loading -> Text(
                "Loading messages…",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
            )
            state.error != null -> Text(
                state.error,
                color = WheelColors.duress,
                fontSize = 14.sp,
            )
            state.messages.isEmpty() -> Text(
                "No messages yet.",
                color = WheelColors.textSecondary,
                fontSize = 14.sp,
            )
            else -> {
                val newestFirst = state.messages.sortedByDescending { it.sentAt }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(newestFirst, key = { it.id }) { message ->
                        MessageListRow(message = message, onClick = onOpenThread)
                        HorizontalDivider(color = WheelColors.border, thickness = 1.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.clickable(onClick = onOpenThread)) {
            Text(
                text = if (state.unreadCount > 0) {
                    "VIEW FULL THREAD & REPLY (${state.unreadCount} unread)"
                } else {
                    "VIEW FULL THREAD & REPLY"
                },
                color = WheelColors.gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun MessageListRow(message: MessageDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderLabel(message),
                    color = WheelColors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.senderType == "dispatch" && message.readAt == null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(WheelColors.gold, shape = CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = message.body,
                color = WheelColors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatMessageRelativeTime(message.sentAt),
            color = WheelColors.textMuted,
            fontSize = 11.sp,
        )
    }
}

private fun senderLabel(message: MessageDto): String =
    if (message.senderType == "dispatch") "Dispatch" else "You"
