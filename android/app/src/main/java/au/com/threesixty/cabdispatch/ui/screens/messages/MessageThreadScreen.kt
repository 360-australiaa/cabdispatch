package au.com.threesixty.cabdispatch.ui.screens.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import au.com.threesixty.cabdispatch.data.remote.MessageDto
import au.com.threesixty.cabdispatch.data.remote.MessageTemplateDto
import au.com.threesixty.cabdispatch.ui.deck.DeckButton
import au.com.threesixty.cabdispatch.ui.deck.DeckButtonKind
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.Deck
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * 24 · Message Thread — Command Deck v2 port (Figma `h0PSsXQ971dOJvt25tN7BA` node `23:65`).
 * Two fixed columns on the 1280 canvas: an 820dp panel-surface thread pane (header, bubbles,
 * bottom composer) and a 460dp canvas-surface quick-tap column. [MessagesViewModel] and every
 * call on it (markUnreadAsRead, sendTemplate/updateOtherNoteText, sendReply/updateComposerText)
 * are unchanged — this pass is layout/tokens only.
 *
 * The frame includes the persistent status strip; this standalone route has no live source for
 * the strip's seven real fields (they belong to WheelDashboardViewModel on the home shell), so —
 * like the other full-screen detail ports (see ShiftStartScreen's precedent) — the screen owns
 * the whole canvas without chrome rather than rendering a strip with faked data.
 *
 * The frame's per-template captions ("Nothing at this rank / address" etc.) have no backing field
 * on [MessageTemplateDto] (code/label only), so cards render the real label; only the OTHER card
 * carries a caption, describing its real expand-a-note behavior.
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

    Row(modifier = Modifier.fillMaxSize().background(Deck.canvas)) {
        // Left — thread pane (Figma 23:91) + composer (23:107) on the panel surface.
        Column(
            modifier = Modifier
                .width(820.dp)
                .fillMaxHeight()
                .background(Deck.panel)
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "←",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Deck.textSecondary,
                    modifier = Modifier.clickable { navController.popBackStack() },
                )
                Text(
                    "Dispatch — thread",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Deck.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                // S6 (settings) reachable from anywhere, per spec — kept from the previous version.
                Text(
                    "⚙",
                    color = Deck.textMuted,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) }
                        .padding(4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> CenteredThreadMessage("Loading messages…")
                    state.messages.isEmpty() -> CenteredThreadMessage("No messages yet — dispatch will reach you here.")
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    }
                }
            }

            listOfNotNull(state.error, state.sendError, state.templateSendError).forEach { err ->
                Text(
                    err,
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = Deck.hired,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Composer row (Figma 23:107): 64dp field + 140dp yellow SEND.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeckTextField(
                    value = state.composerText,
                    onValueChange = viewModel::updateComposerText,
                    placeholder = "Type a message…",
                    modifier = Modifier.weight(1f).height(64.dp),
                )
                if (state.sending) {
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(Deck.R_MD.dp))
                            .background(Deck.yellow),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Deck.onYellow)
                    }
                } else {
                    DeckButton(
                        text = "SEND",
                        kind = DeckButtonKind.Primary,
                        modifier = Modifier.width(140.dp),
                        enabled = state.composerText.isNotBlank(),
                        onClick = viewModel::sendReply,
                    )
                }
            }
        }

        // Right — quick-tap column (Figma 23:112).
        QuickTapColumn(
            templates = state.templates,
            sendingCode = state.sendingTemplateCode,
            otherNoteText = state.otherNoteText,
            onOtherNoteChange = viewModel::updateOtherNoteText,
            onTap = viewModel::sendTemplate,
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
        Text(text, fontFamily = InterFamily, fontSize = 15.sp, color = Deck.textMuted)
    }
}

/** Frame `23:100`'s driver-bubble fill — a blue-steel tone introduced by this frame. */
private val DriverBubble = Color(0xFF20344F)

/** Figma 23:95/23:99 — dispatch bubbles left on the card surface, driver right on [DriverBubble],
 * radius 14, with a Roboto Mono "Dispatch · 4:02 PM" / "You · 4:04 PM" timestamp underneath. */
@Composable
private fun MessageBubble(message: MessageDto) {
    val fromDriver = message.senderType != "dispatch"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromDriver) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (fromDriver) DriverBubble else Deck.card)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(message.body, fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textPrimary)
        }
        Text(
            text = "${if (fromDriver) "You" else "Dispatch"} · ${formatMessageClockTime(message.sentAt)}",
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = Deck.textMuted,
        )
    }
}

/** Matches the backend's `app.services.messages.MESSAGE_TEMPLATES` "other" code exactly — see
 * [MessagesViewModel]'s own private copy of this same constant (kept separate deliberately). */
private const val OTHER_TEMPLATE_CODE = "other"

/**
 * Figma `23:112` — the 460dp quick-tap column: caption, one card per template (yellow label on a
 * card surface with a strong stroke), and the frame's distracted-driving footnote. Every template
 * except "other" sends immediately on tap; "other" expands an optional-note field + SEND instead
 * (real backend behavior — see `TemplateMessageCreate.note`).
 */
@Composable
private fun QuickTapColumn(
    templates: List<MessageTemplateDto>,
    sendingCode: String?,
    otherNoteText: String,
    onOtherNoteChange: (String) -> Unit,
    onTap: (String) -> Unit,
) {
    var otherExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(460.dp)
            .fillMaxHeight()
            .background(Deck.canvas)
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "QUICK-TAP — ONE TAP SENDS",
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Deck.textMuted,
        )
        if (templates.isEmpty()) {
            Text(
                "No quick messages available yet.",
                fontFamily = InterFamily,
                fontSize = 13.sp,
                color = Deck.textMuted,
            )
        }
        templates.forEach { template ->
            val isOther = template.code == OTHER_TEMPLATE_CODE
            val busy = sendingCode == template.code
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Deck.R_MD.dp))
                    .background(Deck.card)
                    .border(1.dp, Deck.strokeStrong, RoundedCornerShape(Deck.R_MD.dp))
                    .alpha(if (sendingCode != null && !busy) 0.5f else 1f)
                    .clickable(enabled = sendingCode == null) {
                        if (isOther) otherExpanded = !otherExpanded else onTap(template.code)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = template.label.uppercase() + if (isOther) "…" else "",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Deck.yellow,
                    )
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Deck.yellow)
                    }
                }
                if (isOther) {
                    Text(
                        "Free text — optional note, sent with the template",
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        color = Deck.textMuted,
                    )
                }
            }
            if (isOther && otherExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeckTextField(
                        value = otherNoteText,
                        onValueChange = onOtherNoteChange,
                        placeholder = "Optional note…",
                        modifier = Modifier.weight(1f).height(64.dp),
                    )
                    DeckButton(
                        text = "SEND",
                        kind = DeckButtonKind.Primary,
                        fontSize = 16,
                        enabled = sendingCode == null,
                        modifier = Modifier.width(96.dp),
                    ) {
                        onTap(OTHER_TEMPLATE_CODE)
                        otherExpanded = false
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Safe at the wheel — templates meet NSW distracted-driving rules.",
            fontFamily = InterFamily,
            fontSize = 13.sp,
            color = Deck.textMuted,
        )
    }
}

/** Deck-toned single-line text field (card fill, strong stroke, radius 14) shared by the composer
 * and the OTHER note input — matches Figma 23:108. */
@Composable
private fun DeckTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(Deck.R_MD.dp))
            .border(1.dp, Deck.strokeStrong, RoundedCornerShape(Deck.R_MD.dp)),
        placeholder = {
            Text(placeholder, fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textMuted)
        },
        textStyle = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, color = Deck.textPrimary),
        singleLine = true,
        shape = RoundedCornerShape(Deck.R_MD.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Deck.card,
            unfocusedContainerColor = Deck.card,
            disabledContainerColor = Deck.card,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = Deck.textPrimary,
            unfocusedTextColor = Deck.textPrimary,
            cursorColor = Deck.yellow,
        ),
    )
}
