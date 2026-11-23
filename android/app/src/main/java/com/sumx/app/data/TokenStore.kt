package com.sumx.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Refresh/access tokens live in Android Keystore-backed encrypted storage. */
class TokenStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "sumx_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var access: String? get() = prefs.getString("a", null); set(v) = prefs.edit().putString("a", v).apply()
    var refresh: String? get() = prefs.getString("r", null); set(v) = prefs.edit().putString("r", v).apply()
    fun clear() = prefs.edit().clear().apply()
}
