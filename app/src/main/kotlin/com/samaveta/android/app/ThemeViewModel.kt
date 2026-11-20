package com.samaveta.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samaveta.android.data.PreferencesRepository
import com.samaveta.android.ui.design.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Theme selection. The preference is stored in DataStore by [PreferencesRepository]; SYSTEM is the
 * default when nothing has been chosen yet.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val systemDarkTheme: SystemThemeObserver,
) : ViewModel() {

    private val _state = MutableStateFlow(ThemeUiState())
    val state: StateFlow<ThemeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.themeMode.collect { mode ->
                _state.value = _state.value.copy(
                    mode = mode ?: ThemeMode.SYSTEM,
                    resolvedIsDark = when (mode ?: ThemeMode.SYSTEM) {
                        ThemeMode.SYSTEM -> systemDarkTheme.isDark()
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    },
                    loaded = true,
                )
            }
        }
    }

    fun setMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }
}

/** Indirection so the ViewModel can be unit-tested without an Android configuration. */
fun interface SystemThemeObserver {
    fun isDark(): Boolean
}
