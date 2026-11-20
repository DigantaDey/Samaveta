package com.samaveta.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.samaveta.android.ui.design.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User preferences.
 *
 * Only non-sensitive configuration lives here (theme, language, display preferences). Anything
 * personal — memories, conversations, voice samples — lives in the encrypted database instead, so
 * that a backup of preferences cannot leak personal data. Cloud backup is disabled entirely in
 * `data_extraction_rules.xml`.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val themeMode: Flow<ThemeMode?> = dataStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    val uiLanguage: Flow<String?> = dataStore.data.map { it[UI_LANGUAGE] }

    suspend fun setUiLanguage(tag: String) {
        dataStore.edit { it[UI_LANGUAGE] = tag }
    }

    val voiceOutputEnabled: Flow<Boolean> = dataStore.data.map { it[VOICE_OUTPUT]?.toBoolean() ?: false }

    suspend fun setVoiceOutputEnabled(enabled: Boolean) {
        dataStore.edit { it[VOICE_OUTPUT] = enabled.toString() }
    }

    val automationPaused: Flow<Boolean> = dataStore.data.map { it[AUTOMATION_PAUSED]?.toBoolean() ?: false }

    suspend fun setAutomationPaused(paused: Boolean) {
        dataStore.edit { it[AUTOMATION_PAUSED] = paused.toString() }
    }

    val emergencyStop: Flow<Boolean> = dataStore.data.map { it[EMERGENCY_STOP]?.toBoolean() ?: false }

    suspend fun setEmergencyStop(engaged: Boolean) {
        dataStore.edit { it[EMERGENCY_STOP] = engaged.toString() }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val UI_LANGUAGE = stringPreferencesKey("ui_language")
        val VOICE_OUTPUT = stringPreferencesKey("voice_output_enabled")
        val AUTOMATION_PAUSED = stringPreferencesKey("automation_paused")
        val EMERGENCY_STOP = stringPreferencesKey("emergency_stop")
    }
}
