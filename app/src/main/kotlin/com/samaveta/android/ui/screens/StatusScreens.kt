package com.samaveta.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.ui.Destination
import com.samaveta.android.ui.PageScaffold
import com.samaveta.android.ui.design.EmptyState
import com.samaveta.android.ui.design.HairlineDivider
import com.samaveta.android.ui.design.SectionHeader
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.StatusPill
import com.samaveta.android.ui.design.StatusTone
import com.samaveta.android.ui.design.SurfaceBlock
import com.samaveta.android.ui.design.TypographyTokens
import com.samaveta.android.ui.design.samaveta
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Surfaces whose subsystem is not yet wired report their real state instead of rendering a fake
 * interface. Each one names the concrete next step so the gap is visible to the user, to QA and to
 * maintainers — the product never shows a "working" screen backed by nothing.
 */
private data class SubsystemStatus(
    val name: String,
    val ready: Boolean,
    val summary: String,
    val blockers: List<String>,
)

@Composable
private fun SubsystemScreen(
    title: String,
    subtitle: String?,
    status: SubsystemStatus,
    bottomInset: Dp,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.samaveta
    PageScaffold(
        title = title,
        subtitle = subtitle,
        trailing = {
            StatusPill(
                label = if (status.ready) "Ready" else "Not available",
                tone = if (status.ready) StatusTone.LIVE else StatusTone.CAUTION,
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomInset + Spacing.xxl),
        ) {
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                Text(status.summary, style = TypographyTokens.body, color = colors.textPrimary)
                if (status.blockers.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.md))
                    status.blockers.forEach { blocker ->
                        Row(Modifier.padding(bottom = Spacing.xs)) {
                            Text("— ", style = TypographyTokens.caption, color = colors.textTertiary)
                            Text(blocker, style = TypographyTokens.caption, color = colors.textSecondary)
                        }
                    }
                }
            }
            extra?.invoke()
        }
    }
}

@Composable
fun ModelsScreen(
    bottomInset: Dp,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SubsystemScreen(
        title = "Models",
        subtitle = state.deviceLine,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Models",
            ready = state.installedCount > 0,
            summary = if (state.installedCount == 0) {
                "No models are installed. SAMAVETA does not ship model weights inside the APK: each model is downloaded on request, verified against its SHA-256, and can be removed at any time."
            } else {
                "${state.installedCount} model(s) installed, using ${state.storageUsedMb} MB."
            },
            blockers = state.blockers,
        ),
        extra = {
            SectionHeader("Runtime")
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                Text(
                    state.runtimeLine,
                    style = TypographyTokens.caption,
                    color = MaterialTheme.samaveta.textSecondary,
                )
            }
        },
    )
}

@Composable
fun VoiceLabScreen(bottomInset: Dp) {
    SubsystemScreen(
        title = "Voice Lab",
        subtitle = null,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Voice",
            ready = false,
            summary = "Voice is not enabled in this build. Speech recognition, on-device synthesis and speaker enrolment are implemented against dedicated models, not the language model, and are the next subsystem to land.",
            blockers = listOf(
                "No ASR model is bundled or downloaded yet.",
                "No TTS voice packages are installed yet.",
                "Microphone permission is requested only when you enable voice.",
                "Speaker identification requires enrolled, consenting profiles and stays on this device.",
            ),
        ),
    )
}

@Composable
fun BrowserScreen(bottomInset: Dp) {
    SubsystemScreen(
        title = "Browser",
        subtitle = null,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Browser",
            ready = false,
            summary = "The agent browser is not enabled in this build. It is a real tabbed browser with DOM extraction, so SAMAVETA reads structured page elements instead of guessing at screen coordinates.",
            blockers = listOf(
                "WebView host, tabs and session isolation are scaffolded but not wired.",
                "Human-verification handoff is a hard requirement and is not yet implemented.",
                "No page content is fetched or summarised by this build.",
            ),
        ),
    )
}

@Composable
fun AutomationScreen(bottomInset: Dp) {
    SubsystemScreen(
        title = "Automation",
        subtitle = null,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Automation",
            ready = false,
            summary = "Automation is disabled. The authorization engine, confirmation binding and audit log are implemented and tested; the Android-side integrations that would perform actions are not yet connected.",
            blockers = listOf(
                "No capability is granted by default — every grant is an explicit user decision.",
                "Notification access, messaging and call capabilities require separate platform permissions.",
                "Nothing will execute until the decision engine is connected and a capability is granted.",
            ),
        ),
    )
}

@Composable
fun MemoryScreen(bottomInset: Dp) {
    SubsystemScreen(
        title = "Memory",
        subtitle = null,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Memory",
            ready = false,
            summary = "Memory is empty. SAMAVETA's memory model — episodic, semantic and procedural, with provenance on every item — is implemented and tested, but no conversation or preference has been recorded yet.",
            blockers = listOf(
                "Interests appear only after you give feedback; nothing is inferred silently.",
                "Every memory item can be inspected, edited and deleted individually.",
                "Speaker profiles and voice samples are never included in backups.",
            ),
        ),
    )
}

@Composable
fun IntelligenceScreen(bottomInset: Dp) {
    SubsystemScreen(
        title = "Intelligence",
        subtitle = null,
        bottomInset = bottomInset,
        status = SubsystemStatus(
            name = "Intelligence",
            ready = false,
            summary = "No topics are configured yet. Intelligence ranks coverage against your interests and shows the publisher, the publication date and any limitation on every card.",
            blockers = listOf(
                "Add at least one topic to start a digest.",
                "Only public RSS and web sources are used; access controls are respected.",
                "Stories based on a headline alone say so instead of claiming a full read.",
            ),
        ),
    )
}

data class ModelsUiState(
    val installedCount: Int = 0,
    val storageUsedMb: Long = 0,
    val deviceLine: String = "",
    val runtimeLine: String = "",
    val blockers: List<String> = emptyList(),
)

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val device: DeviceProfile,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ModelsUiState(
            deviceLine = "${device.manufacturer} ${device.model} · ${device.totalRamMb} MB RAM",
            runtimeLine = "CPU backend available. GPU/NPU execution is not claimed for this build: no device evidence has been collected yet.",
            blockers = listOf(
                "Download the models you need; none are bundled in the APK.",
                "Integrity is checked against a published SHA-256 before a model is used.",
            ),
        ),
    )
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()
}
