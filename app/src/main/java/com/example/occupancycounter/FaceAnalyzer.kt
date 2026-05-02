package com.example.occupancycounter

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * CameraX の ImageAnalysis から ML Kit Face Detection に画像を渡し、
 * 検出された顔の数（=滞在人数）をコールバックで返す。
 */
class FaceAnalyzer(
    private val onFacesDetected: (count: Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: FaceDetector

    init {
        // 高速モード（リアルタイム性重視）
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f) // 画像幅の8%以上の顔を検出（小さくしすぎると誤検出が増える）
            .build()
        detector = FaceDetection.getClient(options)
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(image)
            .addOnSuccessListener { faces ->
                onFacesDetected(faces.size)
            }
            .addOnFailureListener { _ ->
                onFacesDetected(0)
            }
            .addOnCompleteListener {
                // 必ず閉じる（バックプレッシャー対策）
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }
}
