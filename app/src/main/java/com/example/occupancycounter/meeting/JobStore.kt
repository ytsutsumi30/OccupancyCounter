package com.example.occupancycounter.meeting

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * JobStore
 *
 * ジョブ状態を storage/jobs/<jobId>.json にJSON形式で永続化するストア。
 *
 * ディレクトリ: context.filesDir/storage/jobs/
 *
 * JSON スキーマ:
 * {
 *   "job_id":             "20240101-123456-abcd1234",
 *   "status":             "RECORDING|PENDING|UPLOADING|UPLOADED|FAILED",
 *   "device_id":          "AA:BB:CC:DD:EE:FF",
 *   "room_id":            "large" | null,
 *   "title":              "会議タイトル",
 *   "started_at":         "2024-01-01T12:34:56+09:00",
 *   "ended_at":           "2024-01-01T13:34:56+09:00" | null,
 *   "started_at_ms":      1704068096000,
 *   "ended_at_ms":        1704071696000 | 0,
 *   "attendees_estimated":3,
 *   "language":           "ja-JP",
 *   "audio_path":         "/data/.../cache/meetings/xxx.m4a" | null,
 *   "created_at_ms":      1704068096000,
 *   "updated_at_ms":      1704068096000,
 *   "error_message":      null | "...",
 *   "http_code":          null | 200,
 *   "server_job_id":      null | "server-returned-id"
 * }
 */
class JobStore(context: Context) {

    /** ジョブの状態 */
    enum class JobStatus {
        RECORDING,   // 録音中
        PENDING,     // 録音完了・未アップロード
        UPLOADING,   // アップロード中
        UPLOADED,    // アップロード成功
        FAILED       // アップロード失敗
    }

    /** ジョブの全情報を保持するデータクラス */
    data class JobRecord(
        val jobId: String,
        val status: JobStatus,
        val deviceId: String,
        val roomId: String?,
        val title: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val attendeesEstimated: Int,
        val language: String,
        val audioPath: String?,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val errorMessage: String? = null,
        val httpCode: Int? = null,
        val serverJobId: String? = null
    )

    private val jobsDir: File = File(context.filesDir, "storage/jobs").also {
        if (!it.exists()) it.mkdirs()
    }

    // ─────────────────────────────────────────────────────────────
    // 書き込み API
    // ─────────────────────────────────────────────────────────────

    /**
     * 録音開始時に RECORDING 状態でジョブを生成・保存する。
     */
    fun createRecording(
        jobId: String,
        deviceId: String,
        roomId: String?,
        title: String,
        startedAtMs: Long,
        language: String = "ja-JP",
        audioPath: String? = null
    ): JobRecord {
        val now = System.currentTimeMillis()
        val record = JobRecord(
            jobId              = jobId,
            status             = JobStatus.RECORDING,
            deviceId           = deviceId,
            roomId             = roomId,
            title              = title,
            startedAtMs        = startedAtMs,
            endedAtMs          = 0L,
            attendeesEstimated = 0,
            language           = language,
            audioPath          = audioPath,
            createdAtMs        = now,
            updatedAtMs        = now
        )
        write(record)
        return record
    }

    /**
     * 録音停止後に PENDING 状態へ更新する。
     */
    fun markPending(
        jobId: String,
        endedAtMs: Long,
        attendeesEstimated: Int,
        audioPath: String?
    ) {
        updateExisting(jobId) { old ->
            old.copy(
                status             = JobStatus.PENDING,
                endedAtMs          = endedAtMs,
                attendeesEstimated = attendeesEstimated,
                audioPath          = audioPath ?: old.audioPath,
                updatedAtMs        = System.currentTimeMillis()
            )
        }
    }

    /**
     * アップロード開始時に UPLOADING 状態へ更新する。
     */
    fun markUploading(jobId: String) {
        updateExisting(jobId) { old ->
            old.copy(
                status      = JobStatus.UPLOADING,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * アップロード成功時に UPLOADED 状態へ更新する。
     * @param serverJobId サーバが返した job_id (あれば)
     * @param httpCode    HTTP レスポンスコード
     */
    fun markUploaded(jobId: String, serverJobId: String?, httpCode: Int) {
        updateExisting(jobId) { old ->
            old.copy(
                status      = JobStatus.UPLOADED,
                serverJobId = serverJobId,
                httpCode    = httpCode,
                errorMessage= null,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * アップロード失敗時に FAILED 状態へ更新する。
     */
    fun markFailed(jobId: String, errorMessage: String, httpCode: Int?) {
        updateExisting(jobId) { old ->
            old.copy(
                status       = JobStatus.FAILED,
                errorMessage = errorMessage,
                httpCode     = httpCode,
                updatedAtMs  = System.currentTimeMillis()
            )
        }
    }

    /**
     * ジョブを削除する (アップロード成功後のクリーンアップ等)。
     */
    fun delete(jobId: String) {
        val f = fileFor(jobId)
        if (f.exists()) {
            f.delete()
            Log.d(TAG, "Deleted job file: $jobId")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 読み込み API
    // ─────────────────────────────────────────────────────────────

    /** 特定のジョブを読み込む */
    fun load(jobId: String): JobRecord? {
        val f = fileFor(jobId)
        if (!f.exists()) return null
        return try {
            fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read job $jobId", e)
            null
        }
    }

    /** 全ジョブを読み込む (更新日時降順) */
    fun loadAll(): List<JobRecord> {
        return jobsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                try { fromJson(JSONObject(f.readText())) }
                catch (e: Exception) { Log.w(TAG, "Skipping corrupt job file: ${f.name}", e); null }
            }
            ?.sortedByDescending { it.updatedAtMs }
            ?: emptyList()
    }

    /** 指定ステータスのジョブ一覧を返す */
    fun loadByStatus(status: JobStatus): List<JobRecord> = loadAll().filter { it.status == status }

    // ─────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────────────────────

    private fun fileFor(jobId: String): File = File(jobsDir, "$jobId.json")

    private fun write(record: JobRecord) {
        try {
            val f = fileFor(record.jobId)
            f.writeText(toJson(record).toString(2))
            Log.d(TAG, "Saved job ${record.jobId} status=${record.status} → ${f.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write job ${record.jobId}", e)
        }
    }

    private fun updateExisting(jobId: String, transform: (JobRecord) -> JobRecord) {
        val old = load(jobId) ?: run {
            Log.w(TAG, "updateExisting: job not found: $jobId (skipped)")
            return
        }
        write(transform(old))
    }

    // ─────────────────────────────────────────────────────────────
    // JSON シリアライズ
    // ─────────────────────────────────────────────────────────────

    private fun toJson(r: JobRecord): JSONObject = JSONObject().apply {
        put("job_id",              r.jobId)
        put("status",              r.status.name)
        put("device_id",           r.deviceId)
        put("room_id",             r.roomId ?: JSONObject.NULL)
        put("title",               r.title)
        put("started_at",          iso(r.startedAtMs))
        put("ended_at",            if (r.endedAtMs > 0) iso(r.endedAtMs) else JSONObject.NULL)
        put("started_at_ms",       r.startedAtMs)
        put("ended_at_ms",         r.endedAtMs)
        put("attendees_estimated", r.attendeesEstimated)
        put("language",            r.language)
        put("audio_path",          r.audioPath ?: JSONObject.NULL)
        put("created_at_ms",       r.createdAtMs)
        put("updated_at_ms",       r.updatedAtMs)
        put("error_message",       r.errorMessage ?: JSONObject.NULL)
        put("http_code",           r.httpCode ?: JSONObject.NULL)
        put("server_job_id",       r.serverJobId ?: JSONObject.NULL)
    }

    private fun fromJson(j: JSONObject): JobRecord = JobRecord(
        jobId              = j.getString("job_id"),
        status             = runCatching { JobStatus.valueOf(j.getString("status")) }
                               .getOrDefault(JobStatus.PENDING),
        deviceId           = j.optString("device_id", ""),
        roomId             = j.optString("room_id", "").ifEmpty { null },
        title              = j.optString("title", ""),
        startedAtMs        = j.optLong("started_at_ms", 0L),
        endedAtMs          = j.optLong("ended_at_ms", 0L),
        attendeesEstimated = j.optInt("attendees_estimated", 0),
        language           = j.optString("language", "ja-JP"),
        audioPath          = j.optString("audio_path", "").ifEmpty { null },
        createdAtMs        = j.optLong("created_at_ms", 0L),
        updatedAtMs        = j.optLong("updated_at_ms", 0L),
        errorMessage       = j.optString("error_message", "").ifEmpty { null },
        httpCode           = j.optInt("http_code", -1).takeIf { it >= 0 },
        serverJobId        = j.optString("server_job_id", "").ifEmpty { null }
    )

    private fun iso(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    companion object {
        private const val TAG = "JobStore"
    }
}

