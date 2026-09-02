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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import au.com.threesixty.cabdispatch.ui.navigation.CabDispatchRoutes
import au.com.threesixty.cabdispatch.ui.theme.CaptainButton
import au.com.threesixty.cabdispatch.ui.theme.CaptainPalette
import au.com.threesixty.cabdispatch.ui.theme.CaptainPanel
import au.com.threesixty.cabdispatch.ui.theme.InterFamily
import au.com.threesixty.cabdispatch.ui.theme.RobotoMonoFamily

/**
 * 24 · Message Thread — Captain Taxis purple redesign (moved off the yellow/black `Deck` palette
 * onto [CaptainPalette] to match the rest of this dispatch-journey group: trip detail, incoming
 * trip offer, offline sync). Presentation-only: [MessagesViewModel] and every call on it
 * (markUnreadAsRead, sendTemplate/updateOtherNoteText, sendReply/updateComposerText) are unchanged.
 *
 * Layout: a wide thread pane (bubbles + composer) on the left, the quick-tap template column on
 * the right — both rendered as [CaptainPanel] cards under one shared back+title header, matching
 * the [au.com.threesixty.cabdispatch.ui.theme.PaneShell] header used elsewhere in this group. The
 * header keeps a small settings shortcut (S6, reachable from anywhere per spec) as a real
 * [Icons.Rounded.Settings] glyph instead of the previous "⚙" emoji character.
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

    Column(modifier = Modifier.fillMaxSize().background(CaptainPalette.bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable { navController.popBackStack() },
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 24.sp, color = CaptainPalette.textPrimary)
            }
            Text(
                "Messages",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = CaptainPalette.textPrimary,
                modifier = Modifier.padding(start = 16.dp),
            )
            Spacer(Modifier.weight(1f))
            // S6 (settings) reachable from anywhere, per spec — kept from the previous version,
            // now a real Material icon rather than an emoji glyph.
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(CaptainPalette.panel)
                    .border(1.dp, CaptainPalette.panelBorder, CircleShape)
                    .clickable { navController.navigate(CabDispatchRoutes.SETTINGS) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = CaptainPalette.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Left — thread pane + composer.
            CaptainPanel(modifier = Modifier.weight(1.7f).fillMaxHeight()) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
                            fontSize = 13.sp,
                            color = CaptainPalette.danger,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Composer row: 64dp field + 140dp SEND, elderly-friendly touch targets.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CaptainTextField(
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
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CaptainPalette.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = CaptainPalette.textPrimary)
                            }
                        } else {
                            CaptainButton(
                                text = "Send",
                                widthDp = 140,
                                enabled = state.composerText.isNotBlank(),
                                onClick = viewModel::sendReply,
                            )
                        }
                    }
                }
            }

            // Right — quick-tap column.
            QuickTapColumn(
                templates = state.templates,
                sendingCode = state.sendingTemplateCode,
                otherNoteText = state.otherNoteText,
                onOtherNoteChange = viewModel::updateOtherNoteText,
                onTap = viewModel::sendTemplate,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CenteredThreadMessage(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textMuted)
    }
}

/** Dispatch bubbles left on [CaptainPalette.raised], the driver's own messages right on
 * [CaptainPalette.primary], radius 14, with a Roboto Mono "Dispatch · 4:02 PM" / "You · 4:04 PM"
 * timestamp underneath — same structure as before, repainted onto the purple palette. */
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
                .background(if (fromDriver) CaptainPalette.primary else CaptainPalette.raised)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(message.body, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textPrimary)
        }
        Text(
            text = "${if (fromDriver) "You" else "Dispatch"} · ${formatMessageClockTime(message.sentAt)}",
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = CaptainPalette.textMuted,
        )
    }
}

/** Matches the backend's `app.services.messages.MESSAGE_TEMPLATES` "other" code exactly — see
 * [MessagesViewModel]'s own private copy of this same constant (kept separate deliberately). */
private const val OTHER_TEMPLATE_CODE = "other"

/**
 * The quick-tap column: caption, one [CaptainPanel] card per template (accent label on a raised
 * surface), and the distracted-driving footnote. Every template except "other" sends immediately
 * on tap; "other" expands an optional-note field + SEND instead (real backend behavior — see
 * `TemplateMessageCreate.note`).
 */
@Composable
private fun QuickTapColumn(
    templates: List<MessageTemplateDto>,
    sendingCode: String?,
    otherNoteText: String,
    onOtherNoteChange: (String) -> Unit,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var otherExpanded by remember { mutableStateOf(false) }

    CaptainPanel(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "QUICK-TAP — ONE TAP SENDS",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = CaptainPalette.textMuted,
            )
            if (templates.isEmpty()) {
                Text(
                    "No quick messages available yet.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = CaptainPalette.textMuted,
                )
            }
            templates.forEach { template ->
                val isOther = template.code == OTHER_TEMPLATE_CODE
                val busy = sendingCode == template.code
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CaptainPalette.raised)
                        .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp))
                        .alpha(if (sendingCode != null && !busy) 0.5f else 1f)
                        .clickable(enabled = sendingCode == null) {
                            if (isOther) otherExpanded = !otherExpanded else onTap(template.code)
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = template.label.uppercase() + if (isOther) "…" else "",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = CaptainPalette.accent,
                        )
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CaptainPalette.accent)
                        }
                    }
                    if (isOther) {
                        Text(
                            "Free text — optional note, sent with the template",
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            color = CaptainPalette.textMuted,
                        )
                    }
                }
                if (isOther && otherExpanded) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CaptainTextField(
                            value = otherNoteText,
                            onValueChange = onOtherNoteChange,
                            placeholder = "Optional note…",
                            modifier = Modifier.weight(1f).height(64.dp),
                        )
                        CaptainButton(
                            text = "Send",
                            fontSize = 16.sp,
                            heightDp = 64,
                            enabled = sendingCode == null,
                            modifier = Modifier.width(100.dp),
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
                color = CaptainPalette.textMuted,
            )
        }
    }
}

/** Captain-palette single-line text field (inset fill, panel-border stroke, radius 14) shared by
 * the composer and the OTHER note input. */
@Composable
private fun CaptainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, CaptainPalette.panelBorder, RoundedCornerShape(14.dp)),
        placeholder = {
            Text(placeholder, fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textMuted)
        },
        textStyle = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, color = CaptainPalette.textPrimary),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CaptainPalette.inset,
            unfocusedContainerColor = CaptainPalette.inset,
            disabledContainerColor = CaptainPalette.inset,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = CaptainPalette.textPrimary,
            unfocusedTextColor = CaptainPalette.textPrimary,
            cursorColor = CaptainPalette.accent,
        ),
    )
}
