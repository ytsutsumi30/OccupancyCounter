package com.example.occupancycounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.occupancycounter.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * メイン画面: カメラプレビュー + リアルタイム滞在人数表示
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var faceAnalyzer: FaceAnalyzer? = null
    private var serverClient: ServerClient? = null
    private var lastSentTime: Long = 0L
    private var lastCount: Int = -1
    private lateinit var prefs: AppPrefs
    private val clockFormatter = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    // 連続検出した値の安定化用バッファ（チラつき防止）
    private val recentCounts = ArrayDeque<Int>()
    private val smoothingWindow = 5

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.msg_camera_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 画面常時ON（IoTデバイス用途のため）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()
        serverClient = ServerClient(applicationContext)
        prefs = AppPrefs(this)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnMeetingRecord.setOnClickListener {
            startActivity(Intent(this, com.example.occupancycounter.meeting.MeetingActivity::class.java))
        }

        binding.txtCount.text = "0"
        binding.txtStatus.setText(R.string.status_initializing)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                faceAnalyzer = FaceAnalyzer { count ->
                    runOnUiThread { onCountUpdated(count) }
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, faceAnalyzer!!) }

                // カメラ向き（設定で前面/背面を切替可能に）
                val useFrontCamera = prefs.useFrontCamera
                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

                binding.txtStatus.setText(R.string.status_running)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                binding.txtStatus.text = getString(R.string.status_error, e.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCountUpdated(rawCount: Int) {
        // スムージング: 直近 N 回の最頻値を採用
        recentCounts.addLast(rawCount)
        if (recentCounts.size > smoothingWindow) recentCounts.removeFirst()

        val stableCount = recentCounts
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: rawCount

        binding.txtCount.text = stableCount.toString()
        binding.txtRaw.text = getString(R.string.label_raw_count, rawCount)
        binding.txtTime.text = clockFormatter.format(Date())

        // サーバー送信（変化時のみ + 最低送信間隔を守る）
        if (prefs.sendToServer) {
            val now = System.currentTimeMillis()
            val intervalMs = prefs.sendIntervalSec * 1000L
            val changed = stableCount != lastCount
            val intervalElapsed = now - lastSentTime >= intervalMs
            if (changed && intervalElapsed) {
                lastCount = stableCount
                lastSentTime = now

                // confidence 判定:
                //  - スムージングウィンドウが満杯 かつ 全フレームが同じ値 → confirmed
                //  - それ以外（ウォームアップ中、変動中）                → tentative
                val confidence = if (
                    recentCounts.size >= smoothingWindow &&
                    recentCounts.all { it == stableCount }
                ) {
                    ServerClient.Confidence.CONFIRMED
                } else {
                    ServerClient.Confidence.TENTATIVE
                }

                serverClient?.postHeadcount(
                    endpoint = prefs.serverEndpoint,
                    deviceId = prefs.deviceId,
                    headcount = stableCount,
                    confidence = confidence,
                    onResult = { ok, msg ->
                        runOnUiThread {
                            binding.txtServer.text = if (ok) {
                                getString(R.string.label_server_ok, "${msg ?: ""}  [${confidence.label}]")
                            } else {
                                getString(R.string.label_server_ng, msg ?: "")
                            }
                        }
                    }
                )
            }
        } else {
            binding.txtServer.setText(R.string.label_server_off)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceAnalyzer?.close()
    }

    companion object {
        private const val TAG = "OccupancyCounter"
    }
}
