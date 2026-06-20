package com.example.barsafe.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.barsafe.util.BitmapUtils
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class FaceRecognitionHelper(private val context: Context) {

    companion object {
        const val MODEL_FILE = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192

        // Diturunkan ke 0.55f untuk toleransi kondisi backlight/kurang cahaya.
        // Enhancement otomatis pada gambar wajah membantu menjaga akurasi
        // meskipun threshold lebih rendah — bukan asal lolos, tapi gambar
        // yang masuk ke model sudah diperbaiki kualitasnya.
        const val RECOGNITION_THRESHOLD = 0.65f
        private const val TAG = "FaceRecognitionHelper"
    }

    private var interpreter: Interpreter? = null
    val isModelLoaded: Boolean get() = interpreter != null

    init {
        try {
            val options = Interpreter.Options().apply { numThreads = 4 }
            interpreter = Interpreter(loadModelFile(), options)
            Log.d(TAG, "MobileFaceNet model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model '${MODEL_FILE}': ${e.message}")
            interpreter = null
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    /**
     * Extract a 192-dimensional face embedding from a bitmap.
     * Sebelum diproses oleh model, gambar akan di-enhance secara otomatis
     * untuk mengatasi masalah pencahayaan (backlight, kurang cahaya, dll).
     */
    /** Latency terakhir dari inference MobileFaceNet (ms) — untuk tabel pengujian */
    var lastInferenceLatencyMs: Long = 0L
        private set

    /** Latency terakhir dari preprocessing gambar (enhance + resize + normalize) */
    var lastPreprocessLatencyMs: Long = 0L
        private set

    /**
     * Extract embedding dari wajah yang SUDAH di-align ke 112×112.
     *
     * Ini adalah metode UTAMA yang harus dipakai. Face alignment memastikan
     * posisi mata/hidung konsisten di setiap gambar → embedding konsisten →
     * cosine similarity tinggi (0.85-0.95 untuk orang yang sama).
     *
     * @param alignedFace Bitmap 112×112 yang sudah di-align via BitmapUtils.alignFace()
     */
    fun extractEmbeddingAligned(alignedFace: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        val totalStart = System.currentTimeMillis()

        // Step 1: Enhance (jika perlu — gambar yang sudah terang akan di-skip)
        val enhanced = BitmapUtils.enhanceFaceBitmap(alignedFace)
        // Step 2: Pastikan ukuran 112×112 (seharusnya sudah dari alignment)
        val ready = if (enhanced.width != INPUT_SIZE || enhanced.height != INPUT_SIZE)
            Bitmap.createScaledBitmap(enhanced, INPUT_SIZE, INPUT_SIZE, true)
        else enhanced
        // Step 3: Normalize pixel ke [-1, 1]
        val input = preprocessBitmap(ready)
        val preprocessEnd = System.currentTimeMillis()
        lastPreprocessLatencyMs = preprocessEnd - totalStart

        // Step 4: Model inference
        val inferenceStart = System.currentTimeMillis()
        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interp.run(input, output)
        val inferenceEnd = System.currentTimeMillis()
        lastInferenceLatencyMs = inferenceEnd - inferenceStart

        Log.i(TAG, "[LATENCY] Aligned Preprocess: ${lastPreprocessLatencyMs}ms | " +
            "MobileFaceNet Inference: ${lastInferenceLatencyMs}ms | " +
            "Total: ${inferenceEnd - totalStart}ms")

        return l2Normalize(output[0])
    }

    /**
     * Extract embedding tanpa alignment (FALLBACK / legacy).
     * Hanya dipakai jika landmark tidak tersedia.
     * Skor cosine similarity akan lebih rendah (~0.60-0.70).
     */
    fun extractEmbedding(bitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        val totalStart = System.currentTimeMillis()

        // Step 1: Enhance gambar wajah (auto-brightness & contrast)
        val enhanced = BitmapUtils.enhanceFaceBitmap(bitmap)
        // Step 2: Resize ke ukuran input model
        val resized = Bitmap.createScaledBitmap(enhanced, INPUT_SIZE, INPUT_SIZE, true)
        // Step 3: Preprocessing standar MobileFaceNet
        val input = preprocessBitmap(resized)
        val preprocessEnd = System.currentTimeMillis()
        lastPreprocessLatencyMs = preprocessEnd - totalStart

        // Step 4: Model inference
        val inferenceStart = System.currentTimeMillis()
        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interp.run(input, output)
        val inferenceEnd = System.currentTimeMillis()
        lastInferenceLatencyMs = inferenceEnd - inferenceStart

        Log.i(TAG, "[LATENCY] Unaligned Preprocess: ${lastPreprocessLatencyMs}ms | " +
            "MobileFaceNet Inference: ${lastInferenceLatencyMs}ms | " +
            "Total: ${inferenceEnd - totalStart}ms")

        return l2Normalize(output[0])
    }

    /**
     * Preprocessing standar MobileFaceNet: normalisasi pixel ke range [-1, 1].
     * Formula: (pixel - 127.5) / 128
     * Enhancement pencahayaan sudah dilakukan SEBELUM fungsi ini dipanggil.
     */
    private fun preprocessBitmap(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val input = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = bitmap.getPixel(x, y)
                input[0][y][x][0] = (Color.red(pixel) - 127.5f) / 128f
                input[0][y][x][1] = (Color.green(pixel) - 127.5f) / 128f
                input[0][y][x][2] = (Color.blue(pixel) - 127.5f) / 128f
            }
        }
        return input
    }


    private fun l2Normalize(embedding: FloatArray): FloatArray {
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0f) FloatArray(embedding.size) { embedding[it] / norm }
        else embedding
    }

    /**
     * Cosine similarity between two L2-normalized embeddings.
     * Range: [-1, 1], higher = more similar.
     */
    /** Latency terakhir dari cosine similarity matching (ms) — untuk tabel pengujian */
    var lastSimilarityLatencyMs: Long = 0L
        private set

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val start = System.nanoTime()
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        val elapsed = (System.nanoTime() - start) / 1_000 // microseconds
        lastSimilarityLatencyMs = elapsed / 1_000 // ms (biasanya < 1ms)
        return dot
    }

    /**
     * Find the best matching person from a list of stored embeddings.
     * Returns (personId, score) or null if no match above threshold.
     */
    /** Latency total dari proses matching terhadap semua stored embeddings (ms) */
    var lastMatchingTotalLatencyMs: Long = 0L
        private set

    fun findBestMatch(
        probe: FloatArray,
        storedEmbeddings: List<Pair<String, FloatArray>>  // (personId, embedding)
    ): Pair<String, Float>? {
        val matchStart = System.currentTimeMillis()
        var bestId: String? = null
        var bestScore = RECOGNITION_THRESHOLD
        for ((id, emb) in storedEmbeddings) {
            val score = cosineSimilarity(probe, emb)
            Log.d(TAG, "Match score for '$id': $score (threshold: $RECOGNITION_THRESHOLD)")
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        lastMatchingTotalLatencyMs = System.currentTimeMillis() - matchStart
        Log.i(TAG, "[LATENCY] Cosine Similarity Matching (${storedEmbeddings.size} faces): ${lastMatchingTotalLatencyMs}ms")
        return bestId?.let { it to bestScore }
    }

    /**
     * Rata-ratakan beberapa embedding menjadi satu centroid embedding.
     * Centroid lebih representatif daripada single-shot karena mengurangi
     * noise dari variasi pencahayaan, posisi, dan ekspresi.
     *
     * @param embeddings List of embeddings to average
     * @return L2-normalized centroid embedding, atau null jika list kosong
     */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.isEmpty()) return null
        if (embeddings.size == 1) return embeddings[0]

        val centroid = FloatArray(EMBEDDING_SIZE)
        for (emb in embeddings) {
            for (i in centroid.indices) {
                centroid[i] += emb[i]
            }
        }
        for (i in centroid.indices) {
            centroid[i] /= embeddings.size.toFloat()
        }
        return l2Normalize(centroid)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
