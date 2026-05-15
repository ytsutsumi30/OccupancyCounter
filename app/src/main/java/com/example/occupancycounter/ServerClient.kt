package com.example.occupancycounter

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 会議室予約システム (Cloudflare Tunnel 経由) へ滞在人数を送信する。
 *
 * POST /ingest/headcount
 *   Content-Type: application/json
 *   {
 *     "device_id":  "AA:BB:CC:DD:EE:FF",
 *     "headcount":  5,
 *     "confidence": "confirmed"   // "confirmed" | "tentative"
 *   }
 *
 * confidence の意味:
 *   - confirmed: スムージングウィンドウ内の検出値が安定している（推奨）
 *   - tentative: 検出値が変動中（参考値）
 */
class ServerClient(context: Context) {
    private val prefs = AppPrefs(context)

    enum class Confidence(val label: String) {
        CONFIRMED("confirmed"),
        TENTATIVE("tentative")
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun postHeadcount(
        endpoint: String,
        deviceId: String,
        headcount: Int,
        confidence: Confidence = Confidence.CONFIRMED,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        if (endpoint.isBlank()) {
            onResult(false, "endpoint is blank")
            return
        }

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("headcount", headcount)
            put("confidence", confidence.label)
        }.toString()

        val body = json.toRequestBody(JSON_MEDIA)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body)
            .addHeader("Content-Type", "application/json")
        val apiKey = prefs.serverApiKey.trim()
        if (apiKey.isNotEmpty()) requestBuilder.addHeader("X-API-Key", apiKey)
        val request = requestBuilder.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "send failed: ${e.message}")
                onResult(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val ok = resp.isSuccessful
                    Log.d(TAG, "POST $endpoint -> HTTP ${resp.code} (headcount=$headcount, conf=${confidence.label})")
                    onResult(ok, "HTTP ${resp.code}")
                }
            }
        })
    }

    companion object {
        private const val TAG = "ServerClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
