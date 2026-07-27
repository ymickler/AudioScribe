package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("snapscripe_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LANGUAGE = "transcription_language"
        private const val KEY_UI_LANGUAGE = "ui_language"
        private const val KEY_ENCRYPTION = "enable_encryption"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_SHOW_AS_NOTIFICATION = "show_as_notification"
        private const val KEY_STT_ENGINE = "stt_engine"
        private const val KEY_WHISPER_MODEL_SIZE = "whisper_model_size"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PREFER_SERVER_TRANSCRIPTION = "prefer_server_transcription"
        private const val KEY_SERVER_MODEL = "server_model"
    }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var uiLanguage: String
        get() = prefs.getString(KEY_UI_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_UI_LANGUAGE, value).apply()

    var isEncryptionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENCRYPTION, true)
        set(value) = prefs.edit().putBoolean(KEY_ENCRYPTION, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var showAsNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_AS_NOTIFICATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_AS_NOTIFICATION, value).apply()

    var sttEngine: String
        get() = prefs.getString(KEY_STT_ENGINE, "vosk") ?: "vosk"
        set(value) = prefs.edit().putString(KEY_STT_ENGINE, value).apply()

    var whisperModelSize: String
        get() = prefs.getString(KEY_WHISPER_MODEL_SIZE, "tiny") ?: "tiny"
        set(value) = prefs.edit().putString(KEY_WHISPER_MODEL_SIZE, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var preferServerTranscription: Boolean
        get() = prefs.getBoolean(KEY_PREFER_SERVER_TRANSCRIPTION, false)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_SERVER_TRANSCRIPTION, value).apply()

    var serverModel: String
        get() = prefs.getString(KEY_SERVER_MODEL, "tiny") ?: "tiny"
        set(value) = prefs.edit().putString(KEY_SERVER_MODEL, value).apply()

    fun getTargetLanguageCode(): String {
        val selected = language
        if (selected == "system") {
            val sysLanguage = Locale.getDefault().language
            return if (sysLanguage.startsWith("de")) "de" else "en"
        }
        return selected
    }
}
