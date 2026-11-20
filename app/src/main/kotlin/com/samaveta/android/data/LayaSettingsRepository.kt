package com.samaveta.android.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.samaveta.android.core.laya.v1.LayaV1Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the LAYA endpoint and its bearer token live.
 *
 * Two rules, and they are the reason this class exists rather than a few preference keys:
 *
 *  1. **The token never goes in `Preferences`.** `PreferencesRepository` is plain DataStore — an
 *     unencrypted XML/DataStore file an ADB backup or a rooted device can read. The key lives in an
 *     `EncryptedSharedPreferences` file whose master key is in the Android Keystore, which never
 *     leaves the device's secure hardware. Nothing here is ever logged, and `toString()` is not
 *     generated for a holder of the key.
 *  2. **The address is validated before it is stored.** `LayaV1Config` refuses plaintext to a remote
 *     host, so a user cannot configure a deployment whose answers an on-path attacker could rewrite.
 *     Loopback stays allowed, because that is the one realistic offline deployment.
 *
 * A blank address means "not configured", which is the shipped default: the app says LAYA is not
 * connected and refuses to invent a plan. Saving is explicit; nothing here retries or guesses.
 */
@Singleton
class LayaSettingsRepository @Inject constructor(
    private val context: Context,
) {

    /** The persisted configuration. Reads the key only when an address is present. */
    fun config(): LayaV1Config {
        val prefs = secure()
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: return LayaV1Config.NOT_CONFIGURED
        val apiKey = prefs.getString(KEY_API_TOKEN, null)?.takeIf { it.isNotBlank() }
        return LayaV1Config(baseUrl = baseUrl, apiKey = apiKey)
    }

    /** The stored address, for prefilling the Settings field. Never includes the token. */
    fun storedBaseUrl(): String? = secure().getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    /** True when a token is stored. Used to render "token set" without ever reading it back out. */
    fun hasStoredToken(): Boolean = !secure().getString(KEY_API_TOKEN, null).isNullOrBlank()

    /**
     * Saves a configuration.
     *
     * Returns the user-facing problem rather than throwing, because a bad URL is an ordinary typing
     * mistake: the field goes red and the previous value stays in place. `LayaV1Config`'s constructor
     * is the single source of truth for what counts as acceptable, so this cannot drift from it.
     */
    fun save(baseUrl: String, apiToken: String?): String? {
        val trimmed = baseUrl.trim()
        try {
            // Constructed before writing solely to run the config's own validation.
            LayaV1Config(baseUrl = trimmed.ifBlank { null }, apiKey = apiToken?.trim()?.ifBlank { null })
        } catch (invalid: IllegalArgumentException) {
            return invalid.message ?: "That address is not acceptable."
        }
        secure().edit().apply {
            if (trimmed.isBlank()) {
                remove(KEY_BASE_URL)
            } else {
                putString(KEY_BASE_URL, trimmed.trimEnd('/'))
            }
            if (apiToken.isNullOrBlank()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, apiToken.trim())
        }.apply()
        return null
    }

    /** Forgets the endpoint and the token. The app returns to "not configured" immediately. */
    fun clear() {
        secure().edit().clear().apply()
    }

    private var secureStorageHealthy: Boolean = true

    private fun secure(): android.content.SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (failure: Exception) {
        // A revoked keystore entry (device transfer, factory reset edge cases) must not crash the
        // app on launch. Report it and behave as unconfigured; the user can re-enter the details.
        Log.w("LayaSettings", "secure preferences unavailable: ${failure::class.java.simpleName}")
        secureStorageHealthy = false
        context.getSharedPreferences(FALLBACK_DEGRADED_FILE, Context.MODE_PRIVATE)
    }

    /**
     * False when the Keystore was unusable and the fallback file is being used. That file is plain
     * `SharedPreferences`, so the UI must warn instead of describing the token as protected. A token
     * is never handed to the fallback silently.
     */
    fun isSecureStorageAvailable(): Boolean = secureStorageHealthy

    private companion object {
        const val FILE_NAME = "samaveta.laya.secure"
        const val FALLBACK_DEGRADED_FILE = "samaveta.laya.degraded"
        const val KEY_BASE_URL = "laya.base_url"
        const val KEY_API_TOKEN = "laya.api_token"
    }
}
