package com.isym444.networth.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings + last sync outcome in Keystore-backed encrypted preferences (the access key never sits in plain text). */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences? by lazy {
        openWithRecovery(create = ::create, reset = { context.deleteSharedPreferences(FILE) })
    }

    /** Only used if encrypted storage is unavailable: settings last for this process instead of crashing. */
    @Volatile private var inMemory = AppSettings()

    fun load(): AppSettings {
        val p = prefs ?: return inMemory
        return AppSettings(
            baseUrl = p.getString(K_URL, null).orEmpty(),
            apiKey = p.getString(K_KEY, null).orEmpty(),
            webUrl = p.getString(K_WEB, null).orEmpty(),
        )
    }

    fun save(s: AppSettings) {
        val p = prefs
        if (p == null) { inMemory = s; return }
        p.edit().putString(K_URL, s.baseUrl).putString(K_KEY, s.apiKey).putString(K_WEB, s.webUrl).apply()
    }

    fun status(): SyncStatus {
        val p = prefs ?: return SyncStatus(0, null, false)
        return SyncStatus(p.getLong(K_OK, 0), p.getString(K_ERR, null), p.getBoolean(K_REJECTED, false))
    }

    fun recordSync(result: SyncResult, at: Long) {
        val e = (prefs ?: return).edit()
        when (result) {
            is SyncResult.Failed -> e.putString(K_ERR, result.error.userMessage).putBoolean(K_REJECTED, result.error.rejectsKey)
            SyncResult.Updated -> e.putLong(K_OK, at).remove(K_ERR).remove(K_REJECTED)
        }
        e.apply()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context, FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val FILE = "networth_settings"
        const val K_URL = "base_url"; const val K_KEY = "api_key"; const val K_WEB = "web_url"
        const val K_OK = "last_ok"; const val K_ERR = "last_err"; const val K_REJECTED = "key_rejected"
    }
}
