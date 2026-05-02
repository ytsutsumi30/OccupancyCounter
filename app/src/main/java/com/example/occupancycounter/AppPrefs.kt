package com.example.occupancycounter

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.UUID

/**
 * SharedPreferences のラッパー
 */
class AppPrefs(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var sendToServer: Boolean
        get() = prefs.getBoolean(KEY_SEND_TO_SERVER, false)
        set(value) = prefs.edit().putBoolean(KEY_SEND_TO_SERVER, value).apply()

    var serverEndpoint: String
        get() = prefs.getString(KEY_SERVER_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(KEY_SERVER_ENDPOINT, value).apply()

    var deviceId: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_ID, null)
            if (saved != null) return saved
            // 初回起動時にデバイスIDを生成
            val generated = "android-${Build.MODEL?.replace(" ", "_")}-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var sendIntervalSec: Int
        get() = prefs.getString(KEY_SEND_INTERVAL, "10")?.toIntOrNull() ?: 10
        set(value) = prefs.edit().putString(KEY_SEND_INTERVAL, value.toString()).apply()

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_USE_FRONT_CAMERA, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FRONT_CAMERA, value).apply()

    companion object {
        const val KEY_SEND_TO_SERVER = "send_to_server"
        const val KEY_SERVER_ENDPOINT = "server_endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SEND_INTERVAL = "send_interval_sec"
        const val KEY_USE_FRONT_CAMERA = "use_front_camera"

        // プロジェクト指定のサーバー（会議室予約アプリ）
        const val DEFAULT_ENDPOINT = "https://bright-amendments-employer-notebooks.trycloudflare.com/api/occupancy"
    }
}
