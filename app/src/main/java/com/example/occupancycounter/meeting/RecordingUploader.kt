package com.example.occupancycounter.meeting

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * RecordingUploader
 *
 * 録音した m4a ファイルを Express バックエンドへ multipart/form-data でアップロードする。
 *
 * 期待エンドポイント (例):
 *   POST {endpoint}
 *   Content-Type: multipart/form-data; boundary=...
 *
 *   [Part 1] name="meta"
 *     Content-Type: application/json
 *     { "device_id":..., "room_id":..., "title":..., "started_at":..., "ended_at":..., "language":"ja-JP" }
 *
 *   [Part 2] name="audio"; filename="meeting.m4a"
 *     Content-Type: audio/mp4
 *     <binary>
 */
class RecordingUploader(@Suppress("unused") private val context: Context) {

    data class UploadMeta(
        val deviceId: String,
        val roomId: String?,
        val title: String,
        val startedAt: Long,        // epoch ms
        val endedAt: Long,
        val attendeesEstimated: Int,
        val language: String = "ja-JP",
        val jobId: String
    )

    sealed class Result {
        data class Success(val httpCode: Int, val body: String, val jobId: String?): Result()
        data class Failure(val httpCode: Int?, val errorMessage: String): Result()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        // 大きい音声 (60min ≈ 30MB) を送るのでタイムアウト長め
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /**
     * アップロード実行 (非同期)
     */
    fun upload(
        endpoint: String,
        audioFile: File,
        meta: UploadMeta,
        onResult: (Result) -> Unit
    ) {
        require(endpoint.isNotBlank()) { "endpoint is blank" }
        require(audioFile.exists()) { "audio file not found: ${audioFile.absolutePath}" }
        require(audioFile.length() > 0) { "audio file is empty" }

        val metaJson = JSONObject().apply {
            put("device_id", meta.deviceId)
            put("room_id", meta.roomId ?: JSONObject.NULL)
            put("title", meta.title)
            put("started_at", iso(meta.startedAt))
            put("ended_at", iso(meta.endedAt))
            put("attendees_estimated", meta.attendeesEstimated)
            put("language", meta.language)
            put("job_id", meta.jobId)
        }.toString()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "meta",
                filename = null,
                body = metaJson.toRequestBody(JSON_MEDIA)
            )
            .addFormDataPart(
                name = "audio",
                filename = audioFile.name,
                body = audioFile.asRequestBody(AUDIO_MEDIA)
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d(TAG, "Uploading: ${audioFile.name} (${audioFile.length()}B) → $endpoint")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "upload failed: ${e.message}", e)
                onResult(Result.Failure(null, e.message ?: "IOException"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        val jobId = parseJobId(text) ?: meta.jobId
                        Log.d(TAG, "upload success HTTP=${resp.code} jobId=$jobId")
                        onResult(Result.Success(resp.code, text, jobId))
                    } else {
                        Log.w(TAG, "upload http error ${resp.code}: $text")
                        onResult(Result.Failure(resp.code, "HTTP ${resp.code}: ${text.take(200)}"))
                    }
                }
            }
        })
    }

    /**
     * 同期版 (テスト用)
     */
    fun uploadSync(endpoint: String, audioFile: File, meta: UploadMeta): Result {
        val deferred = arrayOfNulls<Result>(1)
        val lock = Object()
        upload(endpoint, audioFile, meta) { r ->
            synchronized(lock) { deferred[0] = r; lock.notifyAll() }
        }
        synchronized(lock) {
            while (deferred[0] == null) lock.wait(30 * 60 * 1000)
        }
        return deferred[0]!!
    }

    private fun parseJobId(body: String): String? {
        return try {
            JSONObject(body).optString("job_id").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private fun iso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    companion object {
        private const val TAG = "RecordingUploader"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val AUDIO_MEDIA = "audio/mp4".toMediaType()
    }
}
