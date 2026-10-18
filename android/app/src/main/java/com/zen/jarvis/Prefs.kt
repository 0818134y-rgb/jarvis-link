package com.zen.jarvis

import android.content.Context
import java.security.SecureRandom
import android.util.Base64

/** 設定値 (SharedPreferences)。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var deviceName: String
        get() = sp.getString("deviceName", "phone") ?: "phone"
        set(v) = sp.edit().putString("deviceName", v.trim().ifBlank { "phone" }).apply()

    /** 連携トークン。PCと同じ値にする。初回はランダム生成。 */
    var token: String
        get() {
            val t = sp.getString("token", null)
            if (!t.isNullOrBlank()) return t
            val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
            val gen = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            sp.edit().putString("token", gen).apply()
            return gen
        }
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var port: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()

    /** 手動ピア。改行またはカンマ区切りの host:port (Tailscale のPCアドレスなど)。 */
    var peers: String
        get() = sp.getString("peers", "") ?: ""
        set(v) = sp.edit().putString("peers", v).apply()

    val peerList: List<String>
        get() = peers.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 常時ウェイクワード待ち受け */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    var allowRemoteDangerous: Boolean
        get() = sp.getBoolean("allowRemoteDangerous", false)
        set(v) = sp.edit().putBoolean("allowRemoteDangerous", v).apply()

    var discovery: Boolean
        get() = sp.getBoolean("discovery", true)
        set(v) = sp.edit().putBoolean("discovery", v).apply()
}
