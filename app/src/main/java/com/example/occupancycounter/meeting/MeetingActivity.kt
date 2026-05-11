package com.example.occupancycounter.meeting

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.occupancycounter.AppPrefs
import com.example.occupancycounter.R
import java.io.File
import kotlin.concurrent.thread

/**
 * MeetingActivity
 *
 * 議事録録音画面。
 *   - 録音開始/停止
 *   - 経過時間表示
 *   - 終了時に Express バックエンドへアップロード
 *   - 録音同意ダイアログ
 */
class MeetingActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var recorder: MeetingRecorder
    private lateinit var uploader: RecordingUploader
    private lateinit var jobStore: JobStore

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtElapsed: TextView
    private lateinit var txtSize: TextView
    private lateinit var txtUploadResult: TextView
    private lateinit var progress: ProgressBar
    private lateinit var editTitle: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateElapsed()
            handler.postDelayed(this, 1000)
        }
    }

    private val requestRecordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            confirmConsentAndStart()
        } else {
            Toast.makeText(this, R.string.msg_record_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting)
        title = getString(R.string.title_meeting_recorder)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs    = AppPrefs(this)
        recorder = MeetingRecorder(applicationContext)
        uploader = RecordingUploader(applicationContext)
        jobStore = JobStore(applicationContext)

        editTitle        = findViewById(R.id.editMeetingTitle)
        btnStart         = findViewById(R.id.btnStartRecording)
        btnStop          = findViewById(R.id.btnStopRecording)
        txtStatus        = findViewById(R.id.txtRecStatus)
        txtElapsed       = findViewById(R.id.txtElapsed)
        txtSize          = findViewById(R.id.txtSize)
        txtUploadResult  = findViewById(R.id.txtUploadResult)
        progress         = findViewById(R.id.progressUpload)

        editTitle.setText(getString(R.string.placeholder_meeting_title))
        btnStop.isEnabled = false

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener { onStopClicked() }

        updateUiForState()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        if (recorder.state == MeetingRecorder.State.RECORDING) {
            recorder.cancel()
        }
    }

    private fun onStartClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        confirmConsentAndStart()
    }

    /** 録音同意ダイアログを表示してOKなら録音開始 */
    private fun confirmConsentAndStart() {
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setPositiveButton(R.string.consent_agree) { _, _ -> startRecording() }
            .setNegativeButton(R.string.consent_disagree, null)
            .setCancelable(true)
            .show()
    }

    private fun startRecording() {
        try {
            val file = recorder.start()
            // ── ジョブ状態を RECORDING で永続化 ──
            jobStore.createRecording(
                jobId       = recorder.jobId,
                deviceId    = prefs.deviceId,
                roomId      = prefs.lastRoomId,
                title       = editTitle.text.toString().ifBlank { getString(R.string.placeholder_meeting_title) },
                startedAtMs = recorder.startedAtMs,
                audioPath   = file.absolutePath
            )
            handler.post(tickRunnable)
            txtUploadResult.text = ""
            updateUiForState()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_rec_start_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun onStopClicked() {
        if (recorder.state != MeetingRecorder.State.RECORDING) return
        try {
            val file = recorder.stop()
            handler.removeCallbacks(tickRunnable)
            // ── ジョブ状態を PENDING に更新 ──
            jobStore.markPending(
                jobId              = recorder.jobId,
                endedAtMs          = recorder.stoppedAtMs,
                attendeesEstimated = 0,
                audioPath          = file.absolutePath
            )
            updateUiForState()
            uploadRecording(file)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_rec_stop_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadRecording(file: File) {
        val endpoint = prefs.minutesEndpoint
        if (endpoint.isBlank()) {
            Toast.makeText(this, R.string.msg_endpoint_not_set, Toast.LENGTH_LONG).show()
            return
        }

        progress.visibility = View.VISIBLE
        txtUploadResult.text = getString(R.string.msg_uploading)

        val meta = RecordingUploader.UploadMeta(
            deviceId          = prefs.deviceId,
            roomId            = prefs.lastRoomId,
            title             = editTitle.text.toString().ifBlank { getString(R.string.placeholder_meeting_title) },
            startedAt         = recorder.startedAtMs,
            endedAt           = recorder.stoppedAtMs,
            attendeesEstimated= 0,
            jobId             = recorder.jobId
        )

        // ── ジョブ状態を UPLOADING に更新 ──
        jobStore.markUploading(recorder.jobId)

        // OkHttp の callback は worker thread で呼ばれるが、UI更新は runOnUiThread で
        thread(name = "uploader") {
            uploader.upload(endpoint, file, meta) { result ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    when (result) {
                        is RecordingUploader.Result.Success -> {
                            txtUploadResult.text = getString(R.string.msg_upload_success, result.jobId ?: "-")
                            // ── ジョブ状態を UPLOADED に更新 ──
                            jobStore.markUploaded(
                                jobId       = meta.jobId,
                                serverJobId = result.jobId,
                                httpCode    = result.httpCode
                            )
                            // アップロード成功 → 一時ファイル削除
                            recorder.cleanup()
                        }
                        is RecordingUploader.Result.Failure -> {
                            txtUploadResult.text = getString(R.string.msg_upload_failed, result.errorMessage)
                            // ── ジョブ状態を FAILED に更新 ──
                            jobStore.markFailed(
                                jobId        = meta.jobId,
                                errorMessage = result.errorMessage,
                                httpCode     = result.httpCode
                            )
                            // 失敗時はファイルを保持 (後で再送可能にする)
                        }
                    }
                    updateUiForState()
                }
            }
        }
    }

    private fun updateElapsed() {
        val sec = recorder.durationSec()
        val mm = sec / 60
        val ss = sec % 60
        txtElapsed.text = String.format("%02d:%02d", mm, ss)
        txtSize.text = recorder.outputFile?.let { "${(it.length() / 1024)} KB" } ?: ""
    }

    private fun updateUiForState() {
        when (recorder.state) {
            MeetingRecorder.State.IDLE, MeetingRecorder.State.STOPPED -> {
                txtStatus.setText(R.string.status_idle)
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
            MeetingRecorder.State.PREPARING -> {
                txtStatus.setText(R.string.status_preparing)
                btnStart.isEnabled = false
                btnStop.isEnabled = false
            }
            MeetingRecorder.State.RECORDING -> {
                txtStatus.setText(R.string.status_recording)
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
            MeetingRecorder.State.ERROR -> {
                txtStatus.setText(R.string.status_error_simple)
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            btnStop.alpha = if (btnStop.isEnabled) 1.0f else 0.5f
        }
    }
}
