package com.example.data

import android.content.Context
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Device-specific credentials never belong in source control or Android backups. */
data class PcProfile(val url: String, val token: String, val caPem: String) {
    companion object {
        fun parse(json: String): PcProfile {
            val obj = JSONObject(json)
            require(obj.getInt("version") == 1) { "Unsupported PC profile" }
            val url = obj.getString("url").trimEnd('/')
            val parsed = url.toHttpUrl()
            require(parsed.isHttps && parsed.encodedPath == "/" && parsed.query == null &&
                parsed.fragment == null && parsed.username.isEmpty() && parsed.password.isEmpty())
            val token = obj.getString("token")
            require(token.length in 32..256 && token.all { it.isLetterOrDigit() || it in "-_" })
            val ca = obj.getString("caPem")
            require(ca.length < 16384 && ca.contains("BEGIN CERTIFICATE"))
            return PcProfile(url, token, ca)
        }
        fun load(context: Context): PcProfile? = runCatching {
            val encrypted = context.getSharedPreferences("pc_credentials", Context.MODE_PRIVATE)
                .getString("profile", null) ?: return@runCatching null
            parse(CryptoHelper.decrypt(encrypted))
        }.getOrNull()
        fun save(context: Context, json: String) {
            parse(json)
            val encrypted = CryptoHelper.encrypt(json)
            check(encrypted.startsWith("ENC:")) { "Secure credential storage unavailable" }
            check(context.getSharedPreferences("pc_credentials", Context.MODE_PRIVATE).edit()
                .putString("profile", encrypted).commit())
        }
        fun clear(context: Context) {
            context.getSharedPreferences("pc_credentials", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
