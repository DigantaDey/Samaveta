package com.samaveta.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.samaveta.android.BuildConfig
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.data.PreferencesRepository
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.ui.Destination
import com.samaveta.android.ui.PageScaffold
import com.samaveta.android.ui.design.Radius
import com.samaveta.android.ui.design.SectionHeader
import com.samaveta.android.ui.design.Spacing
import com.samaveta.android.ui.design.StatusPill
import com.samaveta.android.ui.design.StatusTone
import com.samaveta.android.ui.design.SurfaceBlock
import com.samaveta.android.ui.design.ThemeMode
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
 * Settings. Theme selection is the one control in this build that changes real, persisted state;
 * everything else either reports status honestly or is listed as not yet available.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigate: (Destination) -> Unit,
    bottomInset: Dp,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.samaveta

    PageScaffold(title = "Settings") {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomInset + Spacing.xxl),
        ) {
            SectionHeader("Appearance")
            ThemeSelector(themeMode, onThemeModeChange)
            Hint("System follows your Android theme, including battery saver.")

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("Brand")
            BrandBlock()

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("Decision engine")
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LAYA", style = TypographyTokens.bodyEmphasis, color = colors.textPrimary)
                    Spacer(Modifier.width(Spacing.sm))
                    StatusPill(
                        label = state.layaState.userFacingLabel,
                        tone = if (state.layaState == LayaIntegrationState.VERIFIED) StatusTone.LIVE else StatusTone.CAUTION,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(state.layaExplanation, style = TypographyTokens.caption, color = colors.textSecondary)
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("Safety")
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                ToggleRow(
                    title = "Pause all automation",
                    detail = "Read-only features keep working. Nothing that changes state will run.",
                    checked = state.automationPaused,
                    onChange = viewModel::setAutomationPaused,
                )
                Spacer(Modifier.height(Spacing.md))
                ToggleRow(
                    title = "Emergency stop",
                    detail = "Halts every action immediately. Overrides every permission.",
                    checked = state.emergencyStop,
                    onChange = viewModel::setEmergencyStop,
                    danger = true,
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("Privacy")
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                Text(
                    "All processing happens on this device. SAMAVETA does not upload your messages, screenshots, contacts, voice samples or documents. Cloud backup and device transfer are disabled for all app data.",
                    style = TypographyTokens.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Network access is used only for: model downloads you start, and information sources you add.",
                    style = TypographyTokens.caption,
                    color = colors.textTertiary,
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("About")
            SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
                Text("SAMAVETA 1.0 — SANTOOR", style = TypographyTokens.bodyEmphasis, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = TypographyTokens.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(state.deviceLine, style = TypographyTokens.caption, color = colors.textTertiary)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "SAMAVETA is the platform. LAYA is an independently developed decision engine maintained by a different developer.",
                    style = TypographyTokens.caption,
                    color = colors.textTertiary,
                )
            }

            Spacer(Modifier.height(Spacing.lg))
            SectionHeader("Also available")
            Destination.overflow.forEach { destination ->
                if (destination != Destination.SETTINGS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(destination) }
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(destination.icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Text(destination.label, style = TypographyTokens.body, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSelector(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val colors = MaterialTheme.samaveta
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (selected) colors.accentContainer else colors.surface)
                    .clickable { onChange(mode) }
                    .padding(vertical = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (mode) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    style = if (selected) TypographyTokens.bodyEmphasis else TypographyTokens.body,
                    color = if (selected) colors.accent else colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun BrandBlock() {
    val colors = MaterialTheme.samaveta
    SurfaceBlock(Modifier.padding(horizontal = Spacing.gutter)) {
        Text("SAMAVETA", style = TypographyTokens.title, color = colors.textPrimary)
        // Devanagari brand form, set with the wider leading defined in TypographyTokens so matras
        // are not clipped.
        Text("समवेत", style = TypographyTokens.brandDevanagari, color = colors.accent)
        Text("Intelligence, United.", style = TypographyTokens.caption, color = colors.textSecondary)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = TypographyTokens.caption,
        color = MaterialTheme.samaveta.textTertiary,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.samaveta
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TypographyTokens.bodyEmphasis, color = if (danger) colors.danger else colors.textPrimary)
            Spacer(Modifier.height(Spacing.xxs))
            Text(detail, style = TypographyTokens.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.width(Spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = if (danger) colors.danger else colors.accent,
                uncheckedThumbColor = colors.textTertiary,
                uncheckedTrackColor = colors.surfaceElevated,
            ),
        )
    }
}

data class SettingsUiState(
    val layaState: LayaIntegrationState = LayaIntegrationState.NOT_CONFIGURED,
    val automationPaused: Boolean = false,
    val emergencyStop: Boolean = false,
    val deviceLine: String = "",
) {
    val layaExplanation: String
        get() = when (layaState) {
            LayaIntegrationState.NOT_CONFIGURED ->
                "No production interface has been supplied by LAYA's developer. Autonomous planning and model routing are therefore unavailable in this build. Nothing pretends otherwise."
            LayaIntegrationState.CONFIGURED_UNVERIFIED ->
                "Configured, but not yet verified against the real engine. Treat decisions as unverified."
            LayaIntegrationState.VERIFIED ->
                "Connected and verified. Plans are validated against this device before execution."
            LayaIntegrationState.DEGRADED ->
                "Configured but failing health checks. Task execution is suspended."
            LayaIntegrationState.MOCK_TEST_ONLY ->
                "Development build: a test double is answering. Decisions are simulated and must not be trusted."
        }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @Named("layaState") layaState: LayaIntegrationState,
    private val preferences: PreferencesRepository,
    private val device: DeviceProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            layaState = layaState,
            deviceLine = "${device.manufacturer} ${device.model} · ${device.totalRamMb} MB RAM · API ${device.androidApiLevel}",
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.automationPaused.collect { paused ->
                _state.value = _state.value.copy(automationPaused = paused)
            }
        }
        viewModelScope.launch {
            preferences.emergencyStop.collect { engaged ->
                _state.value = _state.value.copy(emergencyStop = engaged)
            }
        }
    }

    fun setAutomationPaused(paused: Boolean) {
        viewModelScope.launch { preferences.setAutomationPaused(paused) }
    }

    fun setEmergencyStop(engaged: Boolean) {
        viewModelScope.launch { preferences.setEmergencyStop(engaged) }
    }
}
