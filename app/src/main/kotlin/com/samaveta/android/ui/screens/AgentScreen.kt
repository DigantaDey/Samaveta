package com.samaveta.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.core.laya.v1.LayaAgent
import com.samaveta.android.core.laya.v1.LayaV1State
import com.samaveta.android.ui.PageScaffold
import com.samaveta.android.ui.design.EmptyState
import com.samaveta.android.ui.design.Radius
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.StatusPill
import com.samaveta.android.ui.design.StatusTone
import com.samaveta.android.ui.design.TypographyTokens
import com.samaveta.android.ui.design.samaveta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Agent — conversation plus task execution.
 *
 * Layout decision: messages are full-width text blocks separated by space, not left/right bubbles.
 * Bubbles waste horizontal space, force short lines on a phone and make long model output
 * unreadable. The user's input is distinguished by a subtle inset and weight, not a coloured bubble.
 *
 * When LAYA is not configured the composer still works for the capabilities that have a documented
 * local path; requests needing the decision engine say so plainly instead of simulating a plan.
 */
@Composable
fun AgentScreen(
    bottomInset: Dp,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.samaveta
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "Agent",
            subtitle = null,
            trailing = {
                StatusPill(
                    label = state.layaState.userFacingLabel,
                    tone = if (state.layaState == LayaIntegrationState.VERIFIED) StatusTone.LIVE else StatusTone.CAUTION,
                )
            },
        ) {
            if (state.turns.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Send,
                    title = "Ask, attach or speak",
                    body = state.emptyPrompt,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                contentPadding = PaddingValues(
                    start = Spacing.gutter,
                    end = Spacing.gutter,
                    bottom = Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                items(state.turns, key = { it.id }) { turn ->
                    TurnBlock(turn)
                }
                if (state.running != null) {
                    item { RunningBlock(state.running!!) }
                }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            busy = state.running != null,
            canSend = draft.isNotBlank() && state.running == null,
            onSend = {
                viewModel.send(draft)
                draft = ""
            },
            onCancel = viewModel::cancel,
            bottomInset = bottomInset,
        )
    }
}

@Composable
private fun TurnBlock(turn: AgentTurn) {
    val colors = MaterialTheme.samaveta
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (turn.role == TurnRoleUi.USER) "You" else "SAMAVETA",
                style = TypographyTokens.label,
                color = if (turn.role == TurnRoleUi.USER) colors.textTertiary else colors.accent,
            )
            if (turn.origin != null) {
                Spacer(Modifier.width(Spacing.sm))
                Text("· ${turn.origin}", style = TypographyTokens.label, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Box(
            Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (turn.role == TurnRoleUi.USER) colors.surfaceElevated else Color.Transparent)
                .padding(horizontal = if (turn.role == TurnRoleUi.USER) Spacing.md else 0.dp, vertical = if (turn.role == TurnRoleUi.USER) Spacing.sm else 0.dp),
        ) {
            Text(turn.text, style = TypographyTokens.body, color = colors.textPrimary)
        }
        if (turn.disclaimer != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(turn.disclaimer, style = TypographyTokens.caption, color = colors.caution)
        }
    }
}

@Composable
private fun RunningBlock(running: RunningTask) {
    val colors = MaterialTheme.samaveta
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = colors.accent,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(running.label, style = TypographyTokens.body, color = colors.textSecondary)
            if (running.detail != null) {
                Text(running.detail, style = TypographyTokens.caption, color = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    bottomInset: Dp,
) {
    val colors = MaterialTheme.samaveta
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(0.7.dp).background(colors.border))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { /* image attach: wired in perception phase */ }) {
                Icon(Icons.Outlined.Image, contentDescription = "Attach an image", tint = colors.textTertiary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { /* voice input: wired in voice phase */ }) {
                Icon(Icons.Outlined.Mic, contentDescription = "Voice input", tint = colors.textTertiary, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 140.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(colors.surfaceElevated)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
            ) {
                if (draft.isEmpty()) {
                    Text("Message SAMAVETA", style = TypographyTokens.body, color = colors.textTertiary)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    textStyle = TypographyTokens.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            if (busy) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cancel task", tint = colors.danger, modifier = Modifier.size(20.dp))
                }
            } else {
                IconButton(onClick = onSend, enabled = canSend) {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (canSend) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(bottomInset))
    }
}

// ---- ViewModel ---------------------------------------------------------------------------------

enum class TurnRoleUi { USER, ASSISTANT }

data class AgentTurn(
    val id: String,
    val role: TurnRoleUi,
    val text: String,
    val origin: String? = null,
    val disclaimer: String? = null,
)

data class RunningTask(val label: String, val detail: String?)

data class AgentUiState(
    val turns: List<AgentTurn> = emptyList(),
    val running: RunningTask? = null,
    val layaState: LayaIntegrationState = LayaIntegrationState.NOT_CONFIGURED,
) {
    val emptyPrompt: String
        get() = if (layaState == LayaIntegrationState.NOT_CONFIGURED) {
            "The external decision engine is not connected, so plans and model routing are unavailable. " +
                "You can still use direct tools — OCR, summarisation of pasted text, and memory — which " +
                "run locally without it."
        } else {
            "Describe a task. SAMAVETA will ask the decision engine what it is, check the answer against its own " +
                "policy, and report exactly what it did — which, in this build, is never the action itself."
        }
}

@HiltViewModel
class AgentViewModel @Inject constructor(
    @Named("layaState") layaState: LayaIntegrationState,
    private val agent: LayaAgent,
    @Named("clock") private val clock: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentUiState(layaState = layaState))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val seq = _state.value.turns.size
        val userTurn = AgentTurn("turn-u-$seq", TurnRoleUi.USER, trimmed)
        _state.value = _state.value.copy(
            turns = _state.value.turns + userTurn,
            // Named after what is actually happening. "Asking the decision engine" is true; a spinner
            // labelled "Thinking" would imply this build reasons locally, which it does not.
            running = RunningTask("Asking the decision engine", null),
        )

        job = viewModelScope.launch {
            // LAYA is an HTTP call. It runs off the main thread, and `LayaAgent` never throws: a
            // server that is down becomes a sentence, not a crash.
            val reading = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                agent.handle(trimmed, java.util.Locale.getDefault().toLanguageTag())
            }
            val engineState = agent.state()
            _state.value = _state.value.copy(
                turns = _state.value.turns + AgentTurn(
                    id = "turn-a-$seq",
                    role = TurnRoleUi.ASSISTANT,
                    text = reading.userMessage,
                    origin = originOf(reading),
                    // A proposal that was not executed gets a disclaimer; a refusal explains itself.
                    disclaimer = when (reading) {
                        is LayaAgent.Turn.Proposed -> "Nothing was executed. Approval and the capability registry " +
                            "are what would authorise an action, and this build has no executor."
                        else -> null
                    },
                ),
                running = null,
                layaState = engineState.toIntegrationState(),
            )
        }
    }

    private fun originOf(reading: LayaAgent.Turn): String? = when (reading) {
        is LayaAgent.Turn.Proposed -> "proposal"
        is LayaAgent.Turn.Clarify -> "needs detail"
        is LayaAgent.Turn.Conversation -> "no action"
        is LayaAgent.Turn.Refused -> "refused"
        is LayaAgent.Turn.EngineUnavailable -> "engine unavailable"
    }

    /**
     * Maps the v1 connection state onto the legacy status vocabulary the screens were written against.
     * `CONNECTED` maps to `VERIFIED` only because a health-confirmed live server *is* the strongest
     * evidence this app can have; it is not a claim about decision quality, which remains unproven.
     */
    private fun LayaV1State.toIntegrationState(): LayaIntegrationState = when (this) {
        LayaV1State.NOT_CONFIGURED -> LayaIntegrationState.NOT_CONFIGURED
        LayaV1State.NEVER_REACHED -> LayaIntegrationState.CONFIGURED_UNVERIFIED
        LayaV1State.CONNECTED -> LayaIntegrationState.VERIFIED
        LayaV1State.DEGRADED -> LayaIntegrationState.DEGRADED
        // A refused token is not "degraded": nothing works until it is fixed, and saying so is the
        // only way the user knows to correct it in Settings.
        LayaV1State.UNAUTHORISED -> LayaIntegrationState.DEGRADED
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(
            running = null,
            turns = _state.value.turns + AgentTurn(
                id = "turn-a-${_state.value.turns.size}",
                role = TurnRoleUi.ASSISTANT,
                text = "Cancelled. Any step that had already completed will show its verification result in Activity.",
                disclaimer = "Cancelled by you.",
            ),
        )
    }
}
