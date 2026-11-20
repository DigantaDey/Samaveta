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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import com.samaveta.android.data.LayaSettingsRepository
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

                Spacer(Modifier.height(Spacing.md))
                LabeledField(
                    label = "Server address",
                    value = state.baseUrlDraft,
                    placeholder = "https://192.0.2.10:8000",
                    onChange = viewModel::setBaseUrlDraft,
                )
                Spacer(Modifier.height(Spacing.sm))
                LabeledField(
                    label = "Bearer token (optional, stored in the Keystore)",
                    value = state.tokenDraft,
                    placeholder = if (state.hasStoredToken) "A token is stored — type to replace it" else "Not set",
                    onChange = viewModel::setTokenDraft,
                    secret = true,
                )
                if (state.saveError != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(state.saveError!!, style = TypographyTokens.caption, color = colors.danger)
                }
                if (state.saveNotice != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(state.saveNotice!!, style = TypographyTokens.caption, color = colors.accent)
                }
                if (!state.secureStorageAvailable) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "The Keystore is unavailable on this device, so the token would be stored unencrypted. " +
                            "SAMAVETA will not save one in that state.",
                        style = TypographyTokens.caption,
                        color = colors.danger,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentAction("Save", enabled = state.canSave, onClick = viewModel::save)
                    Spacer(Modifier.width(Spacing.md))
                    AccentAction("Clear", enabled = state.hasStoredConfig, onClick = viewModel::clear, danger = true)
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "https is required; plaintext is allowed only for 127.0.0.1, where you run laya-serve yourself. " +
                        "Saving an address does not connect anything: the status only changes after a real call succeeds.",
                    style = TypographyTokens.caption,
                    color = colors.textTertiary,
                )
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
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
) {
    val colors = MaterialTheme.samaveta
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = TypographyTokens.caption, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.xxs))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.surfaceElevated)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = TypographyTokens.body, color = colors.textTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TypographyTokens.body.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) {
                        androidx.compose.ui.text.input.KeyboardType.Password
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Uri
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AccentAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.samaveta
    val tint = when {
        !enabled -> colors.textTertiary
        danger -> colors.danger
        else -> colors.accent
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceElevated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(label, style = TypographyTokens.bodyEmphasis, color = tint)
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
    val baseUrlDraft: String = "",
    val tokenDraft: String = "",
    val hasStoredToken: Boolean = false,
    val hasStoredConfig: Boolean = false,
    val secureStorageAvailable: Boolean = true,
    val saveError: String? = null,
    val saveNotice: String? = null,
) {
    val canSave: Boolean get() = saveError == null && (baseUrlDraft.isNotBlank() || hasStoredConfig)

    /**
     * What the status line means, in the v1 vocabulary.
     *
     * `VERIFIED` here means *a call to this server succeeded*, which is the only thing SAMAVETA can
     * observe. It deliberately does not claim the decisions are good: the engine's quality has not
     * been measured on real weights, and `KNOWN_LIMITATIONS.md` says so.
     */
    val layaExplanation: String
        get() = when (layaState) {
            LayaIntegrationState.NOT_CONFIGURED ->
                "No decision-engine address is set, so task routing is unavailable. Ask the agent anything and it " +
                    "will say exactly that. The rest of the app keeps working."
            LayaIntegrationState.CONFIGURED_UNVERIFIED ->
                "An address is set but no call has succeeded yet. Status changes only after a real response."
            LayaIntegrationState.VERIFIED ->
                "A call to this server succeeded. SAMAVETA applies its own floors and refusal rules to every " +
                    "answer; the engine's decision quality itself has not been measured on real weights."
            LayaIntegrationState.DEGRADED ->
                "Configured but the last call failed. The agent will report the engine's own words rather than " +
                    "guessing."
            LayaIntegrationState.MOCK_TEST_ONLY ->
                "Test build: a test double is answering. Decisions are simulated and must not be trusted."
        }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @Named("layaState") layaState: LayaIntegrationState,
    private val preferences: PreferencesRepository,
    private val layaSettings: LayaSettingsRepository,
    private val device: DeviceProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            layaState = layaState,
            deviceLine = "${device.manufacturer} ${device.model} · ${device.totalRamMb} MB RAM · API ${device.androidApiLevel}",
            baseUrlDraft = layaSettings.storedBaseUrl().orEmpty(),
            hasStoredToken = layaSettings.hasStoredToken(),
            hasStoredConfig = layaSettings.storedBaseUrl() != null,
            secureStorageAvailable = layaSettings.isSecureStorageAvailable(),
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

    fun setBaseUrlDraft(value: String) {
        _state.value = _state.value.copy(baseUrlDraft = value, saveError = null, saveNotice = null)
    }

    fun setTokenDraft(value: String) {
        _state.value = _state.value.copy(tokenDraft = value, saveError = null, saveNotice = null)
    }

    /**
     * Saves the endpoint. A rejected address is shown against the field and the previous value stays
     * in effect — the app never half-applies a configuration.
     */
    fun save() {
        val state = _state.value
        if (!state.secureStorageAvailable && (state.tokenDraft.isNotBlank() || state.hasStoredToken)) {
            _state.value = state.copy(saveError = "The Keystore is unavailable, so a token cannot be stored safely.")
            return
        }
        val problem = layaSettings.save(state.baseUrlDraft, state.tokenDraft)
        _state.value = _state.value.copy(
            saveError = problem,
            saveNotice = if (problem == null) "Saved. The new address is used from the next message." else null,
            tokenDraft = if (problem == null) "" else _state.value.tokenDraft,
            hasStoredToken = layaSettings.hasStoredToken(),
            hasStoredConfig = layaSettings.storedBaseUrl() != null,
            // Saving is not connecting: the pill returns to "not yet reached" until a call succeeds.
            layaState = if (problem == null && layaSettings.storedBaseUrl() != null) {
                LayaIntegrationState.CONFIGURED_UNVERIFIED
            } else {
                _state.value.layaState
            },
        )
    }

    fun clear() {
        layaSettings.clear()
        _state.value = _state.value.copy(
            baseUrlDraft = "",
            tokenDraft = "",
            hasStoredToken = false,
            hasStoredConfig = false,
            saveError = null,
            saveNotice = "Cleared.",
            layaState = LayaIntegrationState.NOT_CONFIGURED,
        )
    }
}
