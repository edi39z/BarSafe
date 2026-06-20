package com.example.barsafe.ml

import com.google.mlkit.vision.face.Face

/**
 * State machine for detecting a single natural blink:
 * IDLE → EYES_OPEN → EYES_CLOSING → BLINK_DETECTED
 */
class LivenessDetector {

    enum class BlinkState { IDLE, EYES_OPEN, EYES_CLOSING, BLINK_DETECTED }

    private var blinkState = BlinkState.IDLE
    private var lastStateChangeMs = 0L

    // Turunkan threshold agar toleran terhadap pantulan kacamata
    private val EYE_OPEN_THRESHOLD = 0.55f
    private val EYE_CLOSED_THRESHOLD = 0.35f
    private val EYES_OPEN_TIMEOUT_MS = 5000L   // Reset if no blink within 5s
    private val EYES_CLOSE_TIMEOUT_MS = 800L   // Max duration of closed phase

    /** Called when a blink is successfully detected */
    var onBlinkDetected: (() -> Unit)? = null

    // ── Timestamp untuk pengukuran latency liveness yang akurat ──────────
    /** Timestamp saat mata terdeteksi stabil terbuka (EYES_OPEN confirmed, setelah 400ms) */
    var eyesConfirmedOpenAt: Long = 0L
        private set
    /** Timestamp saat mata mulai menutup (EYES_CLOSING phase dimulai) */
    var eyesClosingStartAt: Long = 0L
        private set
    /** Timestamp saat blink terdeteksi valid */
    var blinkConfirmedAt: Long = 0L
        private set

    /**
     * Latency pemrosesan liveness detection yang sebenarnya (ms).
     * Dihitung dari mata mulai menutup → blink terkonfirmasi.
     * Ini mengukur kecepatan algoritma, BUKAN waktu reaksi user.
     */
    val actualLivenessProcessingMs: Long?
        get() = if (eyesClosingStartAt > 0L && blinkConfirmedAt > 0L)
            blinkConfirmedAt - eyesClosingStartAt else null

    /**
     * Waktu total siklus blink (mata stabil terbuka → blink terkonfirmasi).
     * Termasuk waktu user untuk memulai kedipan setelah mata dianggap stabil.
     */
    val totalBlinkCycleMs: Long?
        get() = if (eyesConfirmedOpenAt > 0L && blinkConfirmedAt > 0L)
            blinkConfirmedAt - eyesConfirmedOpenAt else null

    fun processFace(face: Face) {
        val leftProb = face.leftEyeOpenProbability ?: return
        val rightProb = face.rightEyeOpenProbability ?: return
        val avgEyeOpen = (leftProb + rightProb) / 2f
        val now = System.currentTimeMillis()
        val elapsed = now - lastStateChangeMs

        // Prevent motion blur spoofing (shaking phone): 
        // Ensure the face is relatively straight and not moving wildly.
        val pitch = face.headEulerAngleX
        val yaw = face.headEulerAngleY
        val roll = face.headEulerAngleZ
        if (kotlin.math.abs(yaw) > 20f || kotlin.math.abs(roll) > 20f || kotlin.math.abs(pitch) > 20f) {
            // Head is tilted or moving fast, reset blink detection
            reset()
            return
        }

        when (blinkState) {
            BlinkState.IDLE, BlinkState.BLINK_DETECTED -> {
                if (avgEyeOpen > EYE_OPEN_THRESHOLD) {
                    blinkState = BlinkState.EYES_OPEN
                    lastStateChangeMs = now
                }
            }

            BlinkState.EYES_OPEN -> {
                when {
                    avgEyeOpen < EYE_CLOSED_THRESHOLD -> {
                        if (elapsed > 400L) {
                            // Eyes must be open and stable for at least 400ms before we accept a closing phase
                            blinkState = BlinkState.EYES_CLOSING
                            lastStateChangeMs = now
                            eyesClosingStartAt = now
                            // Catat bahwa mata sudah confirmed open (stabil > 400ms)
                            if (eyesConfirmedOpenAt == 0L) {
                                eyesConfirmedOpenAt = now - elapsed // waktu saat mulai stabil
                            }
                        } else {
                            // Closed too soon after opening (likely motion blur or camera noise)
                            reset()
                        }
                    }
                    elapsed > EYES_OPEN_TIMEOUT_MS -> {
                        // No blink in time window — reset
                        blinkState = BlinkState.IDLE
                    }
                }
            }

            BlinkState.EYES_CLOSING -> {
                when {
                    avgEyeOpen > EYE_OPEN_THRESHOLD -> {
                        // Eyes reopened → check if the closed duration was realistic
                        if (elapsed > 30L) {
                            // Valid blink! (Real blinks take some time)
                            blinkState = BlinkState.BLINK_DETECTED
                            blinkConfirmedAt = now
                            onBlinkDetected?.invoke()
                        } else {
                            // Too fast, probably noise
                            reset()
                        }
                    }
                    elapsed > EYES_CLOSE_TIMEOUT_MS -> {
                        // Eyes closed too long (not a blink) → reset
                        blinkState = BlinkState.IDLE
                    }
                }
            }
        }
    }

    fun reset() {
        blinkState = BlinkState.IDLE
        lastStateChangeMs = 0L
        eyesConfirmedOpenAt = 0L
        eyesClosingStartAt = 0L
        blinkConfirmedAt = 0L
    }

    val currentState: BlinkState get() = blinkState
}
