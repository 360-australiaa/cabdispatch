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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.InterFamily

/**
 * [au.com.threesixty.cabdispatch.ui.wheel.WheelSlot.MESSAGES] wheel-slot content, per design spec
 * TCT-DRIVER-APP-01.md §4 ("Messages: list of dispatch messages — sender, preview text,
 * timestamp") — a direct Compose port of the reference prototype's `row(...)`-generated
 * `.list-row` markup for the Messages slot (docs/driver-dashboard-full-prototype.html lines
 * ~332-351), newest message first.
 *
 * Phase B v2 reskin (2026-08-26 dock-menu pass, Figma fileKey `JhEhok3n9bntRNS5Y1u3Yc` node
 * `35:2`): rows restyled as left/right chat-style bubbles (dispatch left in a neutral bubble,
 * driver's own replies right in a filled-purple bubble) matching the Figma "Messages" screen's
 * layout, instead of v1's flat list-row table. [MessagesViewModel]/unread-count/[onOpenThread] are
 * unchanged — this file still renders only the content-pane *body*, not the eyebrow/hero title
 * chrome, which the hosting screen owns uniformly for every slot (spec §4 intro).
 *
 * Captain Taxis purple-theme pass (2026-08-29): re-themed off the legacy glass/gold wheel-content
 * palette onto [CaptainPalette] to match the purple `PaneShell` chrome this content is embedded in from
 * [au.com.threesixty.cabdispatch.ui.screens.dashboard.DeckHomeScreen] — colors/typography/shapes
 * only, no behavior change.
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
                fontFamily = InterFamily,
                color = CaptainPalette.textSecondary,
                fontSize = 16.sp,
            )
            error != null -> Text(
                error,
                fontFamily = InterFamily,
                color = CaptainPalette.danger,
                fontSize = 16.sp,
            )
            state.messages.isEmpty() -> Text(
                "No messages yet.",
                fontFamily = InterFamily,
                color = CaptainPalette.textSecondary,
                fontSize = 16.sp,
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

        CaptainButton(
            text = if (state.unreadCount > 0) {
                "VIEW FULL THREAD & REPLY (${state.unreadCount} unread)"
            } else {
                "VIEW FULL THREAD & REPLY"
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
            onClick = onOpenThread,
        )
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
                    Box(modifier = Modifier.size(8.dp).background(CaptainPalette.warning, CircleShape))
                }
                Text(senderLabel(message), fontFamily = InterFamily, color = CaptainPalette.textMuted, fontSize = 12.sp)
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
                            Modifier
                                .background(CaptainPalette.raised, bubbleShape)
                                .border(1.dp, CaptainPalette.panelBorder, bubbleShape)
                        } else {
                            Modifier.background(CaptainPalette.primary, bubbleShape)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.body,
                    fontFamily = InterFamily,
                    color = CaptainPalette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatMessageRelativeTime(message.sentAt),
                fontFamily = InterFamily,
                color = CaptainPalette.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

private fun senderLabel(message: MessageDto): String =
    if (message.senderType == "dispatch") "Dispatch" else "You"
