package com.samaveta.android.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.samaveta.android.core.laya.LayaIntegrationState
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Startup budget: cold launch target is under 2.5 s on the reference device. Therefore nothing
 * heavy happens here — no model loading, no network, no database migration off the main thread.
 * Model runtimes are bound lazily when a feature first needs them.
 */
@HiltAndroidApp
class SamavetaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var startupDiagnostics: StartupDiagnostics

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            // Logging is routed through the redacting logger, never the default worker logger.
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Records the honest integration state for developer diagnostics; deliberately does not
        // throw or block. A missing LAYA contract degrades features, it does not break the app.
        startupDiagnostics.recordStartup()
    }
}

/**
 * Surfaces the state of integrations the user can check at any time. In particular the LAYA status
 * must be visible and must never read as "connected" while the adapter is unconfigured or mocked.
 */
@javax.inject.Singleton
class StartupDiagnostics @javax.inject.Inject constructor(
    @javax.inject.Named("layaState") private val layaState: LayaIntegrationState,
    @javax.inject.Named("buildIsRelease") private val buildIsRelease: Boolean,
) {
    enum class Severity { INFO, WARNING, ERROR }

    data class Diagnostic(val name: String, val detail: String, val severity: Severity)

    private val entries = mutableListOf<Diagnostic>()

    fun recordStartup() {
        entries += when (layaState) {
            LayaIntegrationState.VERIFIED -> Diagnostic(
                "LAYA", "Connected and verified.", Severity.INFO,
            )
            LayaIntegrationState.CONFIGURED_UNVERIFIED -> Diagnostic(
                "LAYA", "Configured but not verified against the real engine.", Severity.WARNING,
            )
            LayaIntegrationState.DEGRADED -> Diagnostic(
                "LAYA", "Configured but health checks are failing.", Severity.ERROR,
            )
            LayaIntegrationState.MOCK_TEST_ONLY -> Diagnostic(
                "LAYA",
                if (buildIsRelease) {
                    "INVALID RELEASE BUILD: mock decision engine active."
                } else {
                    "Mock decision engine active (debug build). Decisions are simulated."
                },
                Severity.WARNING,
            )
            LayaIntegrationState.NOT_CONFIGURED -> Diagnostic(
                "LAYA",
                "Not connected: no production contract is available. Autonomous decision features are unavailable; documented local fallbacks are used where they exist.",
                Severity.WARNING,
            )
        }
    }

    fun diagnostics(): List<Diagnostic> = entries.toList()
}
