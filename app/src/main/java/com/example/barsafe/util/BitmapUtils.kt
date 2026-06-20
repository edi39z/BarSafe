package com.example.barsafe.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BitmapUtils {

    private const val TAG = "BitmapUtils"

    /** Convert Uri from gallery/camera to Bitmap */
    fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Compress bitmap to JPEG bytes for upload */
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Rotate bitmap by given degrees */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Mirror bitmap horizontally (for front camera) */
    fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Crop a face region from a full bitmap using ML Kit bounding box.
     * Adds padding around the face for better recognition.
     */
    fun cropFace(bitmap: Bitmap, boundingBox: Rect, paddingFraction: Float = 0.25f): Bitmap {
        val padX = (boundingBox.width() * paddingFraction).toInt()
        val padY = (boundingBox.height() * paddingFraction).toInt()
        val left = maxOf(0, boundingBox.left - padX)
        val top = maxOf(0, boundingBox.top - padY)
        val right = minOf(bitmap.width, boundingBox.right + padX)
        val bottom = minOf(bitmap.height, boundingBox.bottom + padY)
        val width = right - left
        val height = bottom - top
        return if (width > 0 && height > 0)
            Bitmap.createBitmap(bitmap, left, top, width, height)
        else bitmap
    }

    /** Scale down large bitmaps to avoid OOM */
    fun scaleBitmap(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxSide
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  FACE ALIGNMENT — Kunci utama untuk skor cosine similarity tinggi
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Koordinat referensi standar ArcFace/InsightFace untuk gambar 112×112.
     * MobileFaceNet di-training dengan wajah yang di-align ke posisi ini.
     *
     * Tanpa alignment, posisi mata/hidung bervariasi di setiap foto →
     * embedding tidak konsisten → cosine similarity rendah (~0.60-0.70)
     * meskipun kondisi pencahayaan sudah bagus.
     */
    private val REF_LEFT_EYE  = PointF(30.2946f, 51.6963f)
    private val REF_RIGHT_EYE = PointF(65.5318f, 51.5014f)
    private val REF_NOSE      = PointF(48.0252f, 71.7366f)

    /** Target output alignment = 112×112 (sesuai input MobileFaceNet) */
    private const val ALIGN_SIZE = 112

    /**
     * Align wajah menggunakan posisi mata kiri dan mata kanan dari ML Kit.
     *
     * Proses:
     * 1. Hitung sudut rotasi dari posisi kedua mata
     * 2. Hitung skala berdasarkan jarak antar-mata
     * 3. Buat similarity transformation (rotasi + skala + translasi)
     * 4. Warp gambar ke template 112×112 standar ArcFace
     *
     * Hasilnya: wajah selalu di posisi yang SAMA persis di gambar 112×112,
     * sehingga embedding MobileFaceNet menjadi sangat konsisten.
     *
     * @param bitmap Gambar penuh (sebelum di-crop)
     * @param leftEye Posisi mata kiri dari ML Kit FaceLandmark
     * @param rightEye Posisi mata kanan dari ML Kit FaceLandmark
     * @return Wajah yang sudah di-align ke 112×112, atau null jika gagal
     */
    fun alignFace(
        bitmap: Bitmap,
        leftEye: PointF,
        rightEye: PointF
    ): Bitmap? {
        try {
            // ── 1. Hitung sudut rotasi dari posisi mata ───────────────────
            val dx = rightEye.x - leftEye.x
            val dy = rightEye.y - leftEye.y
            val angle = atan2(dy.toDouble(), dx.toDouble()) * (180.0 / Math.PI)

            // ── 2. Hitung skala: jarak antar-mata di gambar vs di template ─
            val srcDist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val refDist = sqrt(
                ((REF_RIGHT_EYE.x - REF_LEFT_EYE.x) * (REF_RIGHT_EYE.x - REF_LEFT_EYE.x) +
                 (REF_RIGHT_EYE.y - REF_LEFT_EYE.y) * (REF_RIGHT_EYE.y - REF_LEFT_EYE.y)).toDouble()
            ).toFloat()
            val scale = refDist / srcDist

            // ── 3. Hitung titik pusat mata di gambar asli ─────────────────
            val eyeCenter = PointF(
                (leftEye.x + rightEye.x) / 2f,
                (leftEye.y + rightEye.y) / 2f
            )

            // ── 4. Titik pusat mata di template referensi ─────────────────
            val refEyeCenter = PointF(
                (REF_LEFT_EYE.x + REF_RIGHT_EYE.x) / 2f,
                (REF_LEFT_EYE.y + REF_RIGHT_EYE.y) / 2f
            )

            // ── 5. Buat transformation matrix ─────────────────────────────
            // Urutan: rotasi di titik mata → skala → translasi ke posisi referensi
            val matrix = Matrix()
            // Rotasi di sekitar pusat mata agar mata sejajar horizontal
            matrix.postRotate(-angle.toFloat(), eyeCenter.x, eyeCenter.y)
            // Skala agar jarak mata sama dengan template
            matrix.postScale(scale, scale, eyeCenter.x, eyeCenter.y)
            // Translasi agar pusat mata berada di posisi referensi
            matrix.postTranslate(
                refEyeCenter.x - eyeCenter.x,
                refEyeCenter.y - eyeCenter.y
            )

            // ── 6. Warp gambar ke output 112×112 ──────────────────────────
            val aligned = Bitmap.createBitmap(ALIGN_SIZE, ALIGN_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(aligned)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, matrix, paint)

            Log.d(TAG, "Face aligned: angle=${String.format("%.1f", angle)}°, " +
                "scale=${String.format("%.3f", scale)}, " +
                "eyeCenter=(${eyeCenter.x.toInt()},${eyeCenter.y.toInt()})")

            return aligned

        } catch (e: Exception) {
            Log.e(TAG, "Face alignment failed: ${e.message}")
            return null
        }
    }

    /**
     * Enhance gambar wajah untuk mengatasi masalah backlight/kurang cahaya.
     *
     * Teknik yang digunakan:
     * 1. Deteksi kecerahan rata-rata gambar
     * 2. Jika terlalu gelap (<90), naikkan brightness secara proporsional
     * 3. Terapkan contrast stretching (min-max normalization) agar detail
     *    wajah yang "tenggelam" dalam gelap bisa dimunculkan kembali
     *
     * Fungsi ini aman dipanggil pada gambar yang sudah terang — jika gambar
     * sudah cukup cerah, enhancement minimal dilakukan.
     */
    fun enhanceFaceBitmap(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // ── Step 1: Hitung statistik kecerahan (luminance) ─────────────
        var sumBrightness = 0f
        var minLum = 255f
        var maxLum = 0f
        for (pixel in pixels) {
            // Rumus luminance standar (ITU-R BT.601)
            val lum = 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
            sumBrightness += lum
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }
        val avgBrightness = sumBrightness / pixels.size
        Log.d(TAG, "Face image stats — avg: $avgBrightness, min: $minLum, max: $maxLum")

        // ── Step 2: Tentukan apakah gambar perlu di-enhance ────────────
        // Jika gambar sudah cukup cerah (avg > 90) dan kontras sudah baik, skip
        val range = maxLum - minLum
        if (avgBrightness > 90f && range > 100f) {
            Log.d(TAG, "Image brightness OK, skipping enhancement")
            return bitmap
        }

        // ── Step 3: Hitung faktor koreksi ──────────────────────────────
        // Target kecerahan rata-rata = 120 (cukup terang untuk AI)
        val targetBrightness = 120f
        val brightnessFactor = if (avgBrightness > 10f) targetBrightness / avgBrightness else 2.0f

        // Contrast stretching: perluas range warna ke 0-255
        val contrastScale = if (range > 5f) 220f / range else 1f
        val contrastOffset = minLum

        Log.d(TAG, "Enhancing: brightFactor=$brightnessFactor, contrastScale=$contrastScale")

        // ── Step 4: Terapkan enhancement ke setiap pixel ───────────────
        val enhanced = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            var r = Color.red(pixel).toFloat()
            var g = Color.green(pixel).toFloat()
            var b = Color.blue(pixel).toFloat()

            // Contrast stretching terlebih dahulu
            r = (r - contrastOffset) * contrastScale
            g = (g - contrastOffset) * contrastScale
            b = (b - contrastOffset) * contrastScale

            // Brightness boost (blending: 60% contrast-stretched + 40% brightness-boosted)
            val rBoosted = Color.red(pixel) * brightnessFactor
            val gBoosted = Color.green(pixel) * brightnessFactor
            val bBoosted = Color.blue(pixel) * brightnessFactor

            r = r * 0.6f + rBoosted * 0.4f
            g = g * 0.6f + gBoosted * 0.4f
            b = b * 0.6f + bBoosted * 0.4f

            // Clamp ke 0-255
            enhanced[i] = Color.argb(
                255,
                min(255, max(0, r.roundToInt())),
                min(255, max(0, g.roundToInt())),
                min(255, max(0, b.roundToInt()))
            )
        }

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(enhanced, 0, w, 0, 0, w, h)
        return result
    }
}
