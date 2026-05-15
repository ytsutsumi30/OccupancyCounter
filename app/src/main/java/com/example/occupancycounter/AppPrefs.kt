package com.example.occupancycounter

import android.content.Context
import androidx.preference.PreferenceManager
import java.util.UUID

/**
 * SharedPreferences のラッパー
 *
 * 注意: device_id は会議室予約システム側で「会議室の識別子」として使用されるため、
 *       初回起動時に MAC アドレス形式 (AA:BB:CC:DD:EE:FF) でランダム生成し、
 *       ユーザーが設定画面で会議室固有の値に書き換えられるようにする。
 *       Android 6.0 以降は実 MAC アドレス取得が制限されているため、
 *       UUID から擬似的な MAC を生成する。
 */
class AppPrefs(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var sendToServer: Boolean
        get() = prefs.getBoolean(KEY_SEND_TO_SERVER, false)
        set(value) = prefs.edit().putBoolean(KEY_SEND_TO_SERVER, value).apply()

    var serverEndpoint: String
        get() {
            val stored = prefs.getString(KEY_SERVER_ENDPOINT, DEFAULT_ENDPOINT)
            val resolved = resolveServerEndpoint(stored)
            if (resolved != stored) {
                prefs.edit().putString(KEY_SERVER_ENDPOINT, resolved).apply()
            }
            return resolved
        }
        set(value) = prefs.edit().putString(KEY_SERVER_ENDPOINT, value).apply()

    var deviceId: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_ID, null)
            if (saved != null) return saved
            val generated = generatePseudoMac()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var sendIntervalSec: Int
        get() = parseSendIntervalSec(prefs.getString(KEY_SEND_INTERVAL, "10"))
        set(value) = prefs.edit().putString(KEY_SEND_INTERVAL, value.toString()).apply()

    var serverApiKey: String
        get() = prefs.getString(KEY_SERVER_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_API_KEY, value).apply()

    var useFrontCamera: Boolean
        get() = prefs.getBoolean(KEY_USE_FRONT_CAMERA, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_FRONT_CAMERA, value).apply()

    /**
     * 議事録録音アップロード先 (POST /ingest/recording を期待)
     * 例: https://xxx.trycloudflare.com/ingest/recording
     * 空文字なら未設定 (UI で警告表示)
     */
    var minutesEndpoint: String
        get() = prefs.getString(KEY_MINUTES_ENDPOINT, DEFAULT_MINUTES_ENDPOINT) ?: DEFAULT_MINUTES_ENDPOINT
        set(value) = prefs.edit().putString(KEY_MINUTES_ENDPOINT, value).apply()

    /**
     * 議事録対象の会議室ID（large/medium/small/booth など）
     * deviceId のマッピングと併用するための補助情報
     */
    var lastRoomId: String?
        get() = prefs.getString(KEY_LAST_ROOM_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_ROOM_ID, value).apply()

    /**
     * UUID の最初の 12 桁を使って AA:BB:CC:DD:EE:FF 形式の擬似 MAC を生成。
     * 端末再インストール後も同一とは限らない（device_id は SharedPreferences に保存される）。
     */
    private fun generatePseudoMac(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").uppercase().take(12)
        return hex.chunked(2).joinToString(":")
    }

    companion object {
        const val KEY_SEND_TO_SERVER = "send_to_server"
        const val KEY_SERVER_ENDPOINT = "server_endpoint"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_SEND_INTERVAL = "send_interval_sec"
        const val KEY_SERVER_API_KEY = "server_api_key"
        const val KEY_USE_FRONT_CAMERA = "use_front_camera"
        const val KEY_MINUTES_ENDPOINT = "minutes_endpoint"
        const val KEY_LAST_ROOM_ID = "last_room_id"

        const val DEFAULT_MINUTES_ENDPOINT = "https://your-tunnel.trycloudflare.com/ingest/recording"

        // プロジェクト指定の会議室予約システム エンドポイント
        // POST /ingest/headcount に { device_id, headcount, confidence } を送信
        const val DEFAULT_ENDPOINT = "https://supported-eligibility-rogers-warranty.trycloudflare.com/ingest/headcount"

        // 旧エンドポイント（自動移行用）
        const val LEGACY_ENDPOINT = "https://dui-pond-expand-amended.trycloudflare.com/ingest/headcount"
        const val LEGACY_ENDPOINT_API_OCCUPANCY = "https://bright-amendments-employer-notebooks.trycloudflare.com/api/occupancy"

        private val LEGACY_ENDPOINTS = setOf(
            LEGACY_ENDPOINT,
            LEGACY_ENDPOINT_API_OCCUPANCY
        )

        internal fun parseSendIntervalSec(raw: String?): Int {
            return raw?.toIntOrNull() ?: 10
        }

        internal fun resolveServerEndpoint(stored: String?): String {
            val candidate = stored ?: DEFAULT_ENDPOINT
            return if (candidate in LEGACY_ENDPOINTS) DEFAULT_ENDPOINT else candidate
        }
    }
}
