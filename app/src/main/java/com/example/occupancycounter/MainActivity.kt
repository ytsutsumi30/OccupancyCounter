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

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
                val useFrontCamera = AppPrefs(this).useFrontCamera
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
        binding.txtTime.text = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())

        // サーバー送信（変化時のみ + 最低送信間隔を守る）
        val prefs = AppPrefs(this)
        if (prefs.sendToServer) {
            val now = System.currentTimeMillis()
            val intervalMs = prefs.sendIntervalSec * 1000L
            val changed = stableCount != lastCount
            val intervalElapsed = now - lastSentTime >= intervalMs
            if (changed && intervalElapsed) {
                lastCount = stableCount
                lastSentTime = now
                serverClient?.postCount(
                    endpoint = prefs.serverEndpoint,
                    deviceId = prefs.deviceId,
                    count = stableCount,
                    onResult = { ok, msg ->
                        runOnUiThread {
                            binding.txtServer.text = if (ok) {
                                getString(R.string.label_server_ok, msg ?: "")
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
