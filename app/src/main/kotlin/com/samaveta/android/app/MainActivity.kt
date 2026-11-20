package com.samaveta.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samaveta.android.ui.SamavetaRoot
import com.samaveta.android.ui.design.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. Everything else is Compose navigation.
 *
 * Configuration changes are handled by the manifest (`configChanges`) so the process is not
 * recreated on rotation or theme switch; state lives in ViewModels, not the activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate for the splash to hand over correctly.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val state by themeViewModel.state.collectAsStateWithLifecycle()

            SamavetaRoot(
                themeMode = state.mode,
                onThemeModeChange = themeViewModel::setMode,
            )
        }
    }
}

/** Persisted theme choice. Defaults to SYSTEM as required by the product directive. */
data class ThemeUiState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val resolvedIsDark: Boolean = false,
    val loaded: Boolean = false,
)
