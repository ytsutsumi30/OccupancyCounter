package com.example.occupancycounter.meeting

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * MeetingRecorder
 *
 * 会議音声を録音する MediaRecorder のラッパー。
 *
 * 録音仕様:
 *   - フォーマット: m4a (MPEG_4 + AAC)
 *   - サンプリング: 16 kHz (Azure Speech 推奨)
 *   - チャンネル: モノラル
 *   - ビットレート: 64 kbps
 *
 * 出力先: applicationContext.cacheDir/meetings/<jobId>.m4a
 *   一時的に cache に保存し、アップロード成功後に削除する想定
 */
class MeetingRecorder(private val context: Context) {

    enum class State { IDLE, PREPARING, RECORDING, STOPPED, ERROR }

    var state: State = State.IDLE
        private set

    var startedAtMs: Long = 0L
        private set

    var stoppedAtMs: Long = 0L
        private set

    var jobId: String = ""
        private set

    var outputFile: File? = null
        private set

    private var recorder: MediaRecorder? = null

    /**
     * 録音開始。
     * @return 録音ファイルへの参照 (state=RECORDING で確定)
     */
    fun start(): File {
        check(state == State.IDLE || state == State.STOPPED) {
            "Cannot start recording in state=$state"
        }
        state = State.PREPARING
        jobId = generateJobId()
        outputFile = ensureOutputFile(jobId)

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioChannels(1)
            setAudioEncodingBitRate(BITRATE)
            setOutputFile(outputFile!!.absolutePath)
            try {
                prepare()
                start()
            } catch (e: Exception) {
                state = State.ERROR
                Log.e(TAG, "MediaRecorder prepare/start failed", e)
                releaseQuietly()
                throw e
            }
        }

        startedAtMs = System.currentTimeMillis()
        state = State.RECORDING
        Log.d(TAG, "Recording started: jobId=$jobId path=${outputFile?.absolutePath}")
        return outputFile!!
    }

    /**
     * 録音停止。
     * @return 録音ファイル
     */
    fun stop(): File {
        check(state == State.RECORDING) {
            "Cannot stop recording in state=$state"
        }
        try {
            recorder?.apply {
                try { stop() } catch (e: RuntimeException) {
                    // RuntimeException は録音時間が短すぎる(< ~1秒)場合に出る
                    Log.w(TAG, "stop() RuntimeException (short recording?)", e)
                }
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            state = State.ERROR
            throw e
        } finally {
            recorder = null
        }
        stoppedAtMs = System.currentTimeMillis()
        state = State.STOPPED
        val out = outputFile ?: error("outputFile null after stop")
        Log.d(TAG, "Recording stopped: jobId=$jobId size=${out.length()}B duration=${durationSec()}s")
        return out
    }

    /**
     * 中断 (キャンセル)。録音ファイルも削除。
     */
    fun cancel() {
        if (state == State.RECORDING) {
            try { stop() } catch (_: Exception) { /* ignore */ }
        }
        outputFile?.delete()
        outputFile = null
        state = State.IDLE
        Log.d(TAG, "Recording cancelled: jobId=$jobId")
    }

    fun durationSec(): Long {
        if (startedAtMs == 0L) return 0
        val end = if (stoppedAtMs == 0L) System.currentTimeMillis() else stoppedAtMs
        return (end - startedAtMs) / 1000
    }

    /** 録音ファイルを削除して状態をリセット (アップロード後に呼ぶ) */
    fun cleanup() {
        outputFile?.delete()
        outputFile = null
        state = State.IDLE
        jobId = ""
        startedAtMs = 0L
        stoppedAtMs = 0L
    }

    private fun ensureOutputFile(jobId: String): File {
        val dir = File(context.cacheDir, "meetings").apply { if (!exists()) mkdirs() }
        return File(dir, "$jobId.m4a")
    }

    private fun generateJobId(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val short = UUID.randomUUID().toString().substring(0, 8)
        return "$ts-$short"
    }

    private fun releaseQuietly() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    companion object {
        private const val TAG = "MeetingRecorder"
        const val SAMPLE_RATE = 16000
        const val BITRATE = 64_000
    }
}
