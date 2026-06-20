package com.example.barsafe.ui.faceunlock

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.model.AnomalyLog
import com.example.barsafe.data.remote.FirebaseRepository
import com.example.barsafe.data.remote.UnlockResult
import com.example.barsafe.ml.FaceRecognitionHelper
import com.example.barsafe.ml.LivenessDetector
import com.example.barsafe.service.SafeCommandManager
import com.example.barsafe.util.BitmapUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data kelas untuk keperluan pengujian / debugging.
 * Menyimpan nilai similarity, threshold, dan latency setiap tahap unlock.
 */
data class TestingMetrics(
    /** Nilai cosine similarity dari face recognition terakhir (null jika belum ada) */
    val similarityScore: Float? = null,
    /** Skor tertinggi semua kandidat (termasuk yang di bawah threshold) */
    val highestRawScore: Float? = null,
    /** Threshold yang dipakai saat ini */
    val threshold: Float = FaceRecognitionHelper.RECOGNITION_THRESHOLD,
    /** Apakah score di atas threshold */
    val isAboveThreshold: Boolean = false,

    // ═══════════════════════════════════════════════════════════════════
    //  LATENCY PER TAHAPAN — untuk tabel pengujian di laporan skripsi
    // ═══════════════════════════════════════════════════════════════════

    /** Waktu (ms) pemrosesan Liveness Detection (blink cycle: mata menutup → terbuka kembali) */
    val livenessDetectionMs: Long? = null,
    /** Waktu (ms) menunggu user kedip (face detected → blink) — BUKAN latency sistem */
    val userWaitMs: Long? = null,
    /** Waktu (ms) MobileFaceNet Inference saja (tanpa preprocessing) */
    val mobileFaceNetInferenceMs: Long? = null,
    /** Waktu (ms) preprocessing gambar (enhance + resize + normalize) */
    val preprocessMs: Long? = null,
    /** Waktu (ms) Cosine Similarity Matching terhadap semua wajah tersimpan */
    val cosineSimilarityMatchingMs: Long? = null,
    /** Waktu (ms) total recognition (dari blink → hasil match selesai) */
    val recognitionLatencyMs: Long? = null,

    /** Waktu (ms) Firebase write perintah UNLOCK */
    val firebaseWriteMs: Long? = null,
    /** Waktu (ms) menunggu ACK dari ESP32 (Firebase Synchronization) */
    val firebaseSyncMs: Long? = null,
    /** Waktu (ms) ESP32 Response (ACK diterima dari ESP32) */
    val esp32ResponseMs: Long? = null,

    /** Waktu (ms) dari pengiriman perintah UNLOCK → diterima ACK dari ESP32 */
    val commandToAckLatencyMs: Long? = null,
    /** Waktu (ms) dari BLINK_DETECTED → solenoid ACK (end-to-end unlock) */
    val endToEndLatencyMs: Long? = null
)

enum class UnlockState {
    IDLE,
    NO_FACE,
    FACE_DETECTED,
    AWAITING_BLINK,
    BLINK_DETECTED,
    RECOGNIZING,
    SENDING_COMMAND,
    WAITING_ACK,        // Perintah terkirim, tunggu konfirmasi solenoid dari ESP32
    SUCCESS,            // Berhasil (ACK diterima atau sent)
    SUCCESS_ACKED,      // Berhasil + solenoid sudah dikonfirmasi
    QUEUED_OFFLINE,     // Tidak ada internet, perintah disimpan ke antrian
    FAILED_NO_MATCH,
    FAILED_NO_INTERNET,
    MODEL_NOT_FOUND
}

class FaceUnlockViewModel(application: Application) : AndroidViewModel(application) {

    private val faceRecognitionHelper = FaceRecognitionHelper(application)
    private val livenessDetector = LivenessDetector()
    private val firebaseRepo = FirebaseRepository()
    private val db = AppDatabase.getInstance(application)

    private val safeCommandManager = SafeCommandManager(
        context = application,
        firebaseRepo = firebaseRepo,
        anomalyLogDao = db.anomalyLogDao(),
        pendingCommandDao = db.pendingCommandDao()
    )

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            // KRITIS: Aktifkan landmark detection untuk face alignment!
            // Tanpa ini, posisi mata tidak terdeteksi → tidak bisa align → skor rendah.
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    private val _state = MutableStateFlow(UnlockState.IDLE)
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    private val _recognizedName = MutableStateFlow<String?>(null)
    val recognizedName: StateFlow<String?> = _recognizedName.asStateFlow()

    private val _unlockResult = MutableStateFlow<UnlockResult?>(null)
    val unlockResult: StateFlow<UnlockResult?> = _unlockResult.asStateFlow()

    // One-shot event: emit nama orang saat berhasil unlock (trigger notifikasi di UI)
    private val _unlockSuccessEvent = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 1)
    /** Pair<personName, isAcked> */
    val unlockSuccessEvent: SharedFlow<Pair<String, Boolean>> = _unlockSuccessEvent.asSharedFlow()

    // One-shot event: perintah masuk antrian offline
    private val _queuedOfflineEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val queuedOfflineEvent: SharedFlow<String> = _queuedOfflineEvent.asSharedFlow()

    val pendingCommandCount = safeCommandManager.pendingCommandCount
    val isOnline = safeCommandManager.isOnline

    // ── Testing / Debug Metrics ──────────────────────────────────────────────
    private val _testingMetrics = MutableStateFlow(TestingMetrics())
    val testingMetrics: StateFlow<TestingMetrics> = _testingMetrics.asStateFlow()

    // Timestamp internal untuk menghitung latency
    private var blinkDetectedAt: Long = 0L
    private var commandSentAt: Long = 0L
    /** Timestamp saat wajah pertama kali terdeteksi (mulai liveness) */
    private var faceFirstDetectedAt: Long = 0L

    /**
     * FIX: Simpan bitmap terbaik saat mata terbuka penuh (AWAITING_BLINK phase).
     * Sebelumnya pakai frame terakhir saat blink → mata setengah tertutup → embedding buruk.
     * Sekarang kita simpan frame saat mata terbuka (eyeOpenProb > 0.8) dan pakai itu untuk recognition.
     */
    private var bestOpenEyesBitmap: Bitmap? = null
    private var bestOpenEyesScore: Float = 0f

    /**
     * FACE ALIGNMENT: Simpan gambar wajah yang sudah di-align ke template 112×112.
     * Ini yang akan dipakai untuk recognition, BUKAN cropFace biasa.
     * Alignment memastikan posisi mata/hidung konsisten → embedding konsisten
     * → cosine similarity naik drastis dari ~0.70 ke ~0.85-0.95.
     */
    private var bestAlignedBitmap: Bitmap? = null

    private var capturedBitmap: Bitmap? = null
    private var capturedFace: Face? = null
    private var isProcessingFrame = false

    init {
        if (!faceRecognitionHelper.isModelLoaded) {
            _state.value = UnlockState.MODEL_NOT_FOUND
        }
        livenessDetector.onBlinkDetected = { onBlinkDetected() }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessingFrame || _state.value in setOf(
                UnlockState.BLINK_DETECTED, UnlockState.RECOGNIZING,
                UnlockState.SENDING_COMMAND, UnlockState.WAITING_ACK,
                UnlockState.SUCCESS, UnlockState.SUCCESS_ACKED,
                UnlockState.QUEUED_OFFLINE,
                UnlockState.FAILED_NO_MATCH, UnlockState.FAILED_NO_INTERNET
            )
        ) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        isProcessingFrame = true

        val frameBitmap = try {
            val bmp = imageProxy.toBitmap()
            val rotationDeg = imageProxy.imageInfo.rotationDegrees.toFloat()
            BitmapUtils.rotateBitmap(bmp, rotationDeg)
        } catch (e: Exception) {
            null
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    if (_state.value !in setOf(UnlockState.MODEL_NOT_FOUND)) {
                        _state.value = UnlockState.NO_FACE
                    }
                    livenessDetector.reset()
                } else {
                    val face = faces[0]
                    capturedFace = face
                    if (frameBitmap != null) {
                        val croppedFace = BitmapUtils.cropFace(frameBitmap, face.boundingBox)
                        capturedBitmap = croppedFace

                        // ═══════════════════════════════════════════════════
                        // FACE ALIGNMENT menggunakan posisi mata dari ML Kit
                        // ═══════════════════════════════════════════════════
                        val leftEyeLandmark = face.getLandmark(FaceLandmark.LEFT_EYE)
                        val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)

                        // Simpan frame terbaik saat mata terbuka penuh
                        val leftProb = face.leftEyeOpenProbability ?: 0.5f
                        val rightProb = face.rightEyeOpenProbability ?: 0.5f
                        val avgEyeOpen = (leftProb + rightProb) / 2f

                        if (avgEyeOpen > 0.55f && avgEyeOpen > bestOpenEyesScore) {
                            bestOpenEyesBitmap = croppedFace
                            bestOpenEyesScore = avgEyeOpen

                            // Lakukan face alignment jika landmark tersedia
                            if (leftEyeLandmark != null && rightEyeLandmark != null) {
                                val aligned = BitmapUtils.alignFace(
                                    frameBitmap,
                                    leftEyeLandmark.position,
                                    rightEyeLandmark.position
                                )
                                if (aligned != null) {
                                    bestAlignedBitmap = aligned
                                    Log.d("FaceAlignment", "Aligned face saved (eyeOpen=$avgEyeOpen)")
                                }
                            }
                        }
                    }
                    if (_state.value == UnlockState.NO_FACE || _state.value == UnlockState.IDLE) {
                        _state.value = UnlockState.FACE_DETECTED
                        faceFirstDetectedAt = System.currentTimeMillis()
                    }
                    if (_state.value == UnlockState.FACE_DETECTED || _state.value == UnlockState.AWAITING_BLINK) {
                        _state.value = UnlockState.AWAITING_BLINK
                        livenessDetector.processFace(face)
                    }
                }
            }
            .addOnCompleteListener {
                isProcessingFrame = false
                imageProxy.close()
            }
    }

    private fun onBlinkDetected() {
        if (_state.value != UnlockState.AWAITING_BLINK) return
        _state.value = UnlockState.BLINK_DETECTED
        blinkDetectedAt = System.currentTimeMillis()

        // Latency liveness sesungguhnya: waktu pemrosesan blink (bukan waktu tunggu user)
        val actualLivenessMs = livenessDetector.actualLivenessProcessingMs
        // Waktu total dari face detected → blink (termasuk waktu user bengong)
        val totalWaitMs = if (faceFirstDetectedAt > 0L)
            blinkDetectedAt - faceFirstDetectedAt else null

        Log.i("LatencyMonitor", "═══════════════════════════════════════════════════════")
        Log.i("LatencyMonitor", "  TAHAP 1: Liveness Detection")
        Log.i("LatencyMonitor", "  Blink Processing: ${actualLivenessMs ?: "N/A"} ms")
        Log.i("LatencyMonitor", "  (User wait: ${totalWaitMs ?: "N/A"} ms — not counted)")
        Log.i("LatencyMonitor", "═══════════════════════════════════════════════════════")

        viewModelScope.launch {
            _state.value = UnlockState.RECOGNIZING

            // ═══════════════════════════════════════════════════════════════
            // PRIORITAS: Gunakan bestAlignedBitmap (face alignment 112×112)
            // Face alignment = kunci utama untuk skor cosine similarity tinggi.
            // Fallback: bestOpenEyesBitmap → capturedBitmap (tanpa alignment)
            // ═══════════════════════════════════════════════════════════════
            val alignedBitmap = bestAlignedBitmap
            val fallbackBitmap = bestOpenEyesBitmap ?: capturedBitmap
            val useAligned = alignedBitmap != null

            if (alignedBitmap == null && fallbackBitmap == null) {
                _testingMetrics.value = _testingMetrics.value.copy(
                    similarityScore = null, highestRawScore = null,
                    isAboveThreshold = false,
                    livenessDetectionMs = actualLivenessMs,
                    userWaitMs = totalWaitMs,
                    recognitionLatencyMs = System.currentTimeMillis() - blinkDetectedAt
                )
                logLatencySummary("FAILED (no bitmap)")
                recordFailedAttempt()
                _state.value = UnlockState.FAILED_NO_MATCH
                return@launch
            }

            // ── TAHAP 2: MobileFaceNet Inference ──────────────────────────
            val inferenceStart = System.currentTimeMillis()
            val embedding = withContext(Dispatchers.Default) {
                if (useAligned) {
                    Log.i("FaceAlignment", "Using ALIGNED face for recognition (112×112 template)")
                    faceRecognitionHelper.extractEmbeddingAligned(alignedBitmap!!)
                } else {
                    Log.w("FaceAlignment", "FALLBACK: Using unaligned crop (lower similarity expected)")
                    faceRecognitionHelper.extractEmbedding(fallbackBitmap!!)
                }
            }
            val inferenceEnd = System.currentTimeMillis()
            val totalInferenceLatency = inferenceEnd - inferenceStart
            val pureInferenceLatency = faceRecognitionHelper.lastInferenceLatencyMs
            val preprocessLatency = faceRecognitionHelper.lastPreprocessLatencyMs

            Log.i("LatencyMonitor", "  TAHAP 2: MobileFaceNet Inference")
            Log.i("LatencyMonitor", "  Preprocess: ${preprocessLatency}ms | Inference: ${pureInferenceLatency}ms | Total: ${totalInferenceLatency}ms")

            if (embedding == null) {
                _testingMetrics.value = _testingMetrics.value.copy(
                    similarityScore = null, highestRawScore = null,
                    isAboveThreshold = false,
                    livenessDetectionMs = actualLivenessMs,
                    userWaitMs = totalWaitMs,
                    mobileFaceNetInferenceMs = pureInferenceLatency,
                    preprocessMs = preprocessLatency,
                    recognitionLatencyMs = System.currentTimeMillis() - blinkDetectedAt
                )
                logLatencySummary("FAILED (no embedding)")
                recordFailedAttempt()
                _state.value = UnlockState.FAILED_NO_MATCH
                return@launch
            }

            val storedFaces = withContext(Dispatchers.IO) {
                db.faceEmbeddingDao().getAll()
                    .map { it.personId to it.embedding }
            }

            // ── TAHAP 3: Cosine Similarity Matching ───────────────────────
            val matchingStart = System.currentTimeMillis()
            // Hitung semua skor untuk keperluan debug (termasuk yang tidak lolos threshold)
            val allScores = storedFaces.map { (id, emb) ->
                id to faceRecognitionHelper.cosineSimilarity(embedding, emb)
            }
            val highestRaw = allScores.maxOfOrNull { it.second }
            val match = faceRecognitionHelper.findBestMatch(embedding, storedFaces)
            val matchingEnd = System.currentTimeMillis()
            val cosineSimilarityLatency = matchingEnd - matchingStart

            val recognitionDone = System.currentTimeMillis()
            val recognitionLatency = recognitionDone - blinkDetectedAt

            Log.i("LatencyMonitor", "  TAHAP 3: Cosine Similarity Matching")
            Log.i("LatencyMonitor", "  Faces: ${storedFaces.size} | Latency: ${cosineSimilarityLatency}ms")

            if (match != null) {
                // Update metrics: berhasil, di atas threshold
                _testingMetrics.value = _testingMetrics.value.copy(
                    similarityScore = match.second,
                    highestRawScore = highestRaw,
                    threshold = FaceRecognitionHelper.RECOGNITION_THRESHOLD,
                    isAboveThreshold = true,
                    livenessDetectionMs = actualLivenessMs,
                    userWaitMs = totalWaitMs,
                    mobileFaceNetInferenceMs = pureInferenceLatency,
                    preprocessMs = preprocessLatency,
                    cosineSimilarityMatchingMs = cosineSimilarityLatency,
                    recognitionLatencyMs = recognitionLatency
                )
                Log.i("TestingMetrics", "[BERHASIL] Similarity=${match.second} | " +
                    "Threshold=${FaceRecognitionHelper.RECOGNITION_THRESHOLD} | " +
                    "RecognitionLatency=${recognitionLatency}ms")

                val person = withContext(Dispatchers.IO) {
                    db.faceEmbeddingDao().getById(match.first)
                }
                val personName = person?.name ?: "Unknown"
                _recognizedName.value = personName
                _state.value = UnlockState.SENDING_COMMAND
                commandSentAt = System.currentTimeMillis()

                // ── TAHAP 4: Firebase Synchronization ─────────────────────
                Log.i("LatencyMonitor", "  TAHAP 4: Firebase Synchronization")

                // Kirim perintah melalui SafeCommandManager
                val result = withContext(Dispatchers.IO) {
                    safeCommandManager.executeUnlock(personName)
                }
                _unlockResult.value = result

                val ackReceivedAt = System.currentTimeMillis()
                val cmdToAckLatency = ackReceivedAt - commandSentAt
                val endToEndLatency = ackReceivedAt - blinkDetectedAt

                // Ambil breakdown latency dari FirebaseRepository
                val fbBreakdown = firebaseRepo.lastLatencyBreakdown

                Log.i("LatencyMonitor", "  Firebase Write: ${fbBreakdown.firebaseWriteMs}ms")
                Log.i("LatencyMonitor", "  TAHAP 5: ESP32 Response (ACK Wait)")
                Log.i("LatencyMonitor", "  ACK Wait: ${fbBreakdown.ackWaitMs}ms (received=${fbBreakdown.ackReceived})")

                // Update metrics dengan latency command & end-to-end
                _testingMetrics.value = _testingMetrics.value.copy(
                    firebaseWriteMs = fbBreakdown.firebaseWriteMs,
                    firebaseSyncMs = fbBreakdown.totalFirebaseSyncMs,
                    esp32ResponseMs = fbBreakdown.ackWaitMs,
                    commandToAckLatencyMs = cmdToAckLatency,
                    endToEndLatencyMs = endToEndLatency
                )

                // Log summary table lengkap
                logLatencySummary(
                    result = when (result) {
                        is UnlockResult.SuccessAcked -> "SUCCESS_ACKED"
                        is UnlockResult.SuccessSent -> "SUCCESS_SENT"
                        is UnlockResult.QueuedOffline -> "QUEUED_OFFLINE"
                        is UnlockResult.Failed -> "FAILED"
                    }
                )

                when (result) {
                    is UnlockResult.SuccessAcked -> {
                        _state.value = UnlockState.SUCCESS_ACKED
                        _unlockSuccessEvent.tryEmit(personName to true)
                    }
                    is UnlockResult.SuccessSent -> {
                        _state.value = UnlockState.SUCCESS
                        _unlockSuccessEvent.tryEmit(personName to false)
                    }
                    is UnlockResult.QueuedOffline -> {
                        _state.value = UnlockState.QUEUED_OFFLINE
                        _queuedOfflineEvent.tryEmit(personName)
                    }
                    is UnlockResult.Failed -> {
                        _state.value = UnlockState.FAILED_NO_INTERNET
                    }
                }
            } else {
                // Update metrics: gagal, tidak ada yang di atas threshold
                _testingMetrics.value = _testingMetrics.value.copy(
                    similarityScore = null,
                    highestRawScore = highestRaw,
                    threshold = FaceRecognitionHelper.RECOGNITION_THRESHOLD,
                    isAboveThreshold = false,
                    livenessDetectionMs = actualLivenessMs,
                    userWaitMs = totalWaitMs,
                    mobileFaceNetInferenceMs = pureInferenceLatency,
                    preprocessMs = preprocessLatency,
                    cosineSimilarityMatchingMs = cosineSimilarityLatency,
                    recognitionLatencyMs = recognitionLatency
                )
                Log.i("TestingMetrics", "[GAGAL] HighestRaw=${highestRaw} | " +
                    "Threshold=${FaceRecognitionHelper.RECOGNITION_THRESHOLD} | " +
                    "RecognitionLatency=${recognitionLatency}ms")
                logLatencySummary("FAILED_NO_MATCH")
                recordFailedAttempt()
                _state.value = UnlockState.FAILED_NO_MATCH
            }
        }
    }

    /**
     * Log tabel latency lengkap ke Logcat — format tabel untuk laporan skripsi.
     * Bisa dicopy langsung dari Logcat untuk keperluan dokumentasi.
     */
    private fun logLatencySummary(result: String) {
        val m = _testingMetrics.value
        val tag = "LatencyTable"

        Log.i(tag, "")
        Log.i(tag, "╔═══════════════════════════════════════════════════════════════╗")
        Log.i(tag, "║       TABEL LATENCY SISTEM BARSAFE — PENGUJIAN SKRIPSI       ║")
        Log.i(tag, "╠═══════════════════════════════════════════════════════════════╣")
        Log.i(tag, "║ Tahapan Sistem                │ Waktu (ms)                   ║")
        Log.i(tag, "╠═══════════════════════════════╪══════════════════════════════╣")
        Log.i(tag, String.format("║ %-29s │ %28s ║", "1. Liveness Detection",
            "${m.livenessDetectionMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "   └─ User Wait (excluded)",
            "${m.userWaitMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "2. MobileFaceNet Inference",
            "${m.mobileFaceNetInferenceMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "   └─ Preprocessing",
            "${m.preprocessMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "3. Cosine Similarity Matching",
            "${m.cosineSimilarityMatchingMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "4. Firebase Synchronization",
            "${m.firebaseSyncMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "   └─ Firebase Write",
            "${m.firebaseWriteMs ?: "—"} ms"))
        Log.i(tag, String.format("║ %-29s │ %28s ║", "5. ESP32 Response (ACK)",
            "${m.esp32ResponseMs ?: "—"} ms"))
        Log.i(tag, "╠═══════════════════════════════╪══════════════════════════════╣")
        Log.i(tag, String.format("║ %-29s │ %28s ║", "Total End-to-End",
            "${m.endToEndLatencyMs ?: "—"} ms"))
        Log.i(tag, "╠═══════════════════════════════╧══════════════════════════════╣")
        Log.i(tag, String.format("║ Similarity Score : %-10s │ Threshold : %-14s ║",
            m.similarityScore?.let { "%.4f".format(it) } ?: "—",
            "%.2f".format(m.threshold)))
        Log.i(tag, String.format("║ Result : %-52s ║", result))
        Log.i(tag, "╚═══════════════════════════════════════════════════════════════╝")
        Log.i(tag, "")
    }

    private suspend fun recordFailedAttempt() {
        withContext(Dispatchers.IO) {
            db.anomalyLogDao().insert(
                AnomalyLog(
                    type = "UNLOCK_FAILED",
                    level = "WARNING",
                    description = "Wajah tidak dikenali — akses ditolak"
                )
            )
        }
    }

    fun reset() {
        _state.value = if (faceRecognitionHelper.isModelLoaded) UnlockState.IDLE else UnlockState.MODEL_NOT_FOUND
        _recognizedName.value = null
        _unlockResult.value = null
        _testingMetrics.value = TestingMetrics()
        capturedBitmap = null
        capturedFace = null
        bestOpenEyesBitmap = null
        bestOpenEyesScore = 0f
        bestAlignedBitmap = null
        blinkDetectedAt = 0L
        commandSentAt = 0L
        faceFirstDetectedAt = 0L
        livenessDetector.reset()
    }

    override fun onCleared() {
        super.onCleared()
        faceRecognitionHelper.close()
        faceDetector.close()
        safeCommandManager.unregister()
    }
}
