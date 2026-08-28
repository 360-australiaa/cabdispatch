package au.com.threesixty.cabdispatch.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.ui.theme.WheelColorsV2

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.MESSAGES] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Messages: list of dispatch messages — sender, preview text,
 * timestamp") — a direct Compose port of the reference prototype's `row(...)`-generated
 * `.list-row` markup for the Messages slot (docs/driver-dashboard-full-prototype.html lines
 * ~332-351), newest message first.
 *
 * Phase B v2 reskin (2026-08-26 dock-menu pass, Figma fileKey `JhEhok3n9bntRNS5Y1u3Yc` node
 * `35:2`): rows restyled as left/right chat-style bubbles (dispatch left in a neutral glass
 * bubble, driver's own replies right in a gold bubble) matching the Figma "Messages" screen's
 * layout, instead of v1's flat list-row table. [MessagesViewModel]/unread-count/[onOpenThread] are
 * unchanged — this file still renders only the content-pane *body*, not the eyebrow/hero title
 * chrome, which the hosting screen owns uniformly for every slot (spec §4 intro).
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
        val error = state.error
        when {
            state.loading -> Text(
                "Loading messages…",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            error != null -> Text(
                error,
                color = WheelColorsV2.dangerText,
                fontSize = 14.sp,
            )
            state.messages.isEmpty() -> Text(
                "No messages yet.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )
            else -> {
                val oldestFirst = state.messages.sortedBy { it.sentAt }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(oldestFirst, key = { it.id }) { message ->
                        MessageBubbleRow(message = message, onClick = onOpenThread)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(WheelColorsV2.greenCtaBrush)
                .clickable(onClick = onOpenThread)
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = if (state.unreadCount > 0) {
                    "VIEW FULL THREAD & REPLY (${state.unreadCount} unread)"
                } else {
                    "VIEW FULL THREAD & REPLY"
                },
                color = WheelColorsV2.onGreenCta,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun MessageBubbleRow(message: MessageDto, onClick: () -> Unit) {
    val fromDispatch = message.senderType == "dispatch"
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = if (fromDispatch) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (fromDispatch) Alignment.Start else Alignment.End,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 3.dp, start = 4.dp, end = 4.dp),
            ) {
                if (fromDispatch && message.readAt == null) {
                    Box(modifier = Modifier.size(6.dp).background(WheelColorsV2.amberFigure, CircleShape))
                }
                Text(senderLabel(message), color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
            val bubbleShape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (fromDispatch) 4.dp else 16.dp,
                bottomEnd = if (fromDispatch) 16.dp else 4.dp,
            )
            Box(
                modifier = Modifier
                    .then(
                        if (fromDispatch) {
                            Modifier.background(Color(0xEB221B3E), bubbleShape)
                        } else {
                            Modifier.background(WheelColorsV2.goldCtaBrush, bubbleShape)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.body,
                    color = if (fromDispatch) Color.White.copy(alpha = 0.94f) else WheelColorsV2.onGoldCta,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatMessageRelativeTime(message.sentAt),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

private fun senderLabel(message: MessageDto): String =
    if (message.senderType == "dispatch") "Dispatch" else "You"
