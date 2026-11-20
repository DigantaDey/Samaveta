package com.samaveta.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.samaveta.android.BuildConfig
import com.samaveta.android.app.SystemThemeObserver
import com.samaveta.android.core.device.Accelerator
import com.samaveta.android.core.device.AcceleratorAvailability
import com.samaveta.android.core.device.AcceleratorSupport
import com.samaveta.android.core.device.DeviceCapabilities
import com.samaveta.android.core.device.DeviceProfile
import com.samaveta.android.core.laya.LayaAdapter
import com.samaveta.android.core.laya.LayaConfig
import com.samaveta.android.core.laya.LayaIntegrationState
import com.samaveta.android.core.laya.v1.LayaAgent
import com.samaveta.android.core.laya.v1.LayaUrlTransport
import com.samaveta.android.core.laya.v1.LayaV1Client
import com.samaveta.android.data.LayaSettingsRepository
import com.samaveta.android.models.AndroidDeviceProfileProvider
import com.samaveta.android.models.AndroidRuntimeProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // Corruption must not brick the app: preferences are recoverable, memories are not,
            // so only preferences use the replace-on-corruption handler.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("samaveta.preferences") },
        )

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSystemThemeObserver(@ApplicationContext context: Context): SystemThemeObserver =
        SystemThemeObserver {
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

    /**
     * LAYA integration state.
     *
     * There is no production LAYA endpoint yet, so this is NOT_CONFIGURED by construction rather
     * than by accident. Swapping in a real transport is a single change in this provider once the
     * contract is supplied — see docs/LAYA_INTEGRATION.md.
     */
    @Provides
    @Singleton
    @Named("laya")
    fun provideLayaAdapter(): LayaAdapter = LayaAdapter(
        config = LayaConfig.NOT_CONFIGURED,
        transport = null,
    )

    @Provides
    @Singleton
    @Named("layaState")
    fun provideLayaState(@Named("laya") adapter: LayaAdapter): LayaIntegrationState = adapter.state

    /**
     * The real LAYA v1 client.
     *
     * Constructed from what the user actually stored — address from encrypted preferences, bearer
     * token from the Keystore-backed file — and with the real `LayaUrlTransport`, not a stand-in.
     * When nothing is stored this yields `NOT_CONFIGURED`, which is the honest shipped default:
     * `LayaAgent` then answers "set its address in Settings" instead of pretending to plan.
     *
     * A bad stored value cannot be allowed to crash DI: a config that fails validation collapses to
     * NOT_CONFIGURED, and the Settings screen is where the user sees and repairs it.
     */
    @Provides
    @Singleton
    fun provideLayaV1Client(settings: LayaSettingsRepository): LayaV1Client {
        val config = try {
            settings.config()
        } catch (invalid: IllegalArgumentException) {
            LayaV1Config.NOT_CONFIGURED
        }
        return LayaV1Client(
            config = config,
            transport = if (config.isConfigured) LayaUrlTransport(apiKey = config.apiKey) else null,
        )
    }

    /**
     * The agent turn. `canExecute = false` is a statement of fact, not a placeholder: no capability
     * executor exists in this build, so every proposal must say nothing ran.
     */
    @Provides
    @Singleton
    fun provideLayaAgent(client: LayaV1Client): LayaAgent = LayaAgent(client = client, canExecute = false)

    @Provides
    @Singleton
    @Named("buildIsRelease")
    fun provideBuildIsRelease(): Boolean = !BuildConfig.DEBUG

    @Provides
    @Singleton
    fun provideDeviceProfileProvider(
        @ApplicationContext context: Context,
    ): AndroidDeviceProfileProvider = AndroidDeviceProfileProvider(context)

    @Provides
    @Singleton
    fun provideDeviceProfile(provider: AndroidDeviceProfileProvider): DeviceProfile = provider.snapshot()

    /**
     * Runtime capability probe.
     *
     * Only CPU is reported AVAILABLE unconditionally. GPU/NPU are reported from the probe result,
     * and an unprobed accelerator stays UNKNOWN. The product must never claim NPU acceleration
     * without device evidence.
     */
    @Provides
    @Singleton
    fun provideDeviceCapabilities(@ApplicationContext context: Context): DeviceCapabilities {
        val probe = AndroidRuntimeProbe(context)
        val gpu = probe.gpuAvailability()
        return DeviceCapabilities(
            support = listOf(
                AcceleratorSupport(Accelerator.CPU, AcceleratorAvailability.AVAILABLE, "CPU always available"),
                AcceleratorSupport(Accelerator.GPU, gpu.availability, gpu.evidence),
                // NPU requires a device-specific backend that has not been validated on this build.
                AcceleratorSupport(
                    Accelerator.NPU,
                    AcceleratorAvailability.UNKNOWN,
                    "NNAPI/Vendor NPU backend not validated for this model set; no claim is made.",
                ),
            ),
            backendVersion = probe.backendVersion(),
        )
    }
}
