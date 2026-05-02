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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Cloudflare 経由の会議室予約アプリへ滞在人数を送信する。
 * POST {endpoint}
 *   Content-Type: application/json
 *   {
 *     "device_id": "android-xxx",
 *     "count": 3,
 *     "timestamp": "2026-05-02T10:23:45Z"
 *   }
 */
class ServerClient(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    fun postCount(
        endpoint: String,
        deviceId: String,
        count: Int,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        if (endpoint.isBlank()) {
            onResult(false, "endpoint is blank")
            return
        }

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("count", count)
            put("timestamp", isoNow())
        }.toString()

        val body = json.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "send failed: ${e.message}")
                onResult(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val ok = resp.isSuccessful
                    onResult(ok, "HTTP ${resp.code}")
                }
            }
        })
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    companion object {
        private const val TAG = "ServerClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
