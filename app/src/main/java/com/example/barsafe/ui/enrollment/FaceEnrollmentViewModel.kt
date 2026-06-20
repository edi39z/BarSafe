package com.example.barsafe.ui.enrollment

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.model.FaceEmbedding
import com.example.barsafe.ml.FaceRecognitionHelper
import com.example.barsafe.util.BitmapUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class EnrollmentStep { INTRO, CAPTURE, CONFIRM, SAVING, DONE, ERROR }

class FaceEnrollmentViewModel(application: Application) : AndroidViewModel(application) {

    private val recognitionHelper = FaceRecognitionHelper(application)
    private val db = AppDatabase.getInstance(application)
    private val ctx = application.applicationContext

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // KRITIS: Aktifkan landmark untuk face alignment
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    companion object {
        const val REQUIRED_CAPTURES = 3 // Jumlah foto yang dibutuhkan untuk enrollment
    }

    private val _step = MutableStateFlow(EnrollmentStep.INTRO)
    val step: StateFlow<EnrollmentStep> = _step.asStateFlow()

    private val _capturedBitmaps = MutableStateFlow<List<Bitmap>>(emptyList())
    val capturedBitmaps: StateFlow<List<Bitmap>> = _capturedBitmaps.asStateFlow()

    // Untuk backward compat — single bitmap untuk ConfirmStep
    private val _capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedBitmap: StateFlow<Bitmap?> = _capturedBitmap.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Progress capture: "Foto 1/3", "Foto 2/3", dll */
    private val _captureProgress = MutableStateFlow(0)
    val captureProgress: StateFlow<Int> = _captureProgress.asStateFlow()

    private val pendingEmbeddings = mutableListOf<FloatArray>()
    private val capturedFaces = mutableListOf<Bitmap>()

    val isModelLoaded: Boolean get() = recognitionHelper.isModelLoaded

    fun startCapture() {
        _step.value = EnrollmentStep.CAPTURE
        _captureProgress.value = capturedFaces.size
    }

    fun onFaceCaptured(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        _errorMessage.value = "Wajah tidak terdeteksi. Coba lagi."
                        _step.value = EnrollmentStep.ERROR
                        return@addOnSuccessListener
                    }
                    val face = faces[0]
                    val croppedFace = BitmapUtils.cropFace(bitmap, face.boundingBox)

                    // Face alignment: gunakan posisi mata untuk align wajah ke template 112×112
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

                    val embedding = if (leftEye != null && rightEye != null) {
                        val aligned = BitmapUtils.alignFace(
                            bitmap, leftEye.position, rightEye.position
                        )
                        if (aligned != null) {
                            recognitionHelper.extractEmbeddingAligned(aligned)
                        } else {
                            recognitionHelper.extractEmbedding(croppedFace)
                        }
                    } else {
                        recognitionHelper.extractEmbedding(croppedFace)
                    }

                    if (embedding == null) {
                        _errorMessage.value = "Gagal memproses kerangka wajah. Coba lagi."
                        _step.value = EnrollmentStep.ERROR
                        return@addOnSuccessListener
                    }

                    // Simpan embedding & bitmap
                    pendingEmbeddings.add(embedding)
                    capturedFaces.add(croppedFace)
                    _captureProgress.value = capturedFaces.size

                    if (capturedFaces.size >= REQUIRED_CAPTURES) {
                        // Semua foto sudah diambil → ke step konfirmasi
                        _capturedBitmaps.value = capturedFaces.toList()
                        _capturedBitmap.value = capturedFaces.first() // Preview utama
                        _step.value = EnrollmentStep.CONFIRM
                    } else {
                        // Masih perlu foto lagi → kembali ke capture
                        _step.value = EnrollmentStep.CAPTURE
                    }
                }
                .addOnFailureListener {
                    _errorMessage.value = "Gagal mendeteksi wajah: ${it.message}"
                    _step.value = EnrollmentStep.ERROR
                }
        }
    }

    fun retake() {
        // Reset semua capture dan mulai ulang
        pendingEmbeddings.clear()
        capturedFaces.clear()
        _capturedBitmaps.value = emptyList()
        _capturedBitmap.value = null
        _captureProgress.value = 0
        _step.value = EnrollmentStep.CAPTURE
    }

    fun saveEnrollment(name: String) {
        if (pendingEmbeddings.isEmpty()) return

        viewModelScope.launch {
            _step.value = EnrollmentStep.SAVING

            // Hitung centroid embedding dari semua foto
            val centroidEmbedding = withContext(Dispatchers.Default) {
                recognitionHelper.averageEmbeddings(pendingEmbeddings)
            }
            if (centroidEmbedding == null) {
                _errorMessage.value = "Gagal menghitung embedding rata-rata."
                _step.value = EnrollmentStep.ERROR
                return@launch
            }

            // Simpan foto pertama sebagai foto profil
            val bitmap = capturedFaces.firstOrNull()
            val photoUrl = if (bitmap != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = BitmapUtils.bitmapToJpegBytes(
                            BitmapUtils.scaleBitmap(bitmap, 600), 85
                        )
                        val fileName = "face_${UUID.randomUUID()}.jpg"
                        val file = java.io.File(ctx.filesDir, fileName)
                        file.writeBytes(bytes)
                        file.absolutePath
                    } catch (e: Exception) {
                        ""
                    }
                }
            } else ""

            withContext(Dispatchers.IO) {
                db.faceEmbeddingDao().insert(
                    FaceEmbedding(
                        personId = UUID.randomUUID().toString(),
                        name = name.trim(),
                        photoUrl = photoUrl,
                        embedding = centroidEmbedding
                    )
                )
            }
            _step.value = EnrollmentStep.DONE
        }
    }

    fun reset() {
        _step.value = EnrollmentStep.INTRO
        _capturedBitmap.value = null
        _capturedBitmaps.value = emptyList()
        _errorMessage.value = null
        _captureProgress.value = 0
        pendingEmbeddings.clear()
        capturedFaces.clear()
    }

    override fun onCleared() {
        super.onCleared()
        recognitionHelper.close()
        faceDetector.close()
    }
}
