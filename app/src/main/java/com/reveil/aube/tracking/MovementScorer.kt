package com.reveil.aube.tracking

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A lightweight, on-device movement scorer inspired by classical actigraphy scoring
 * (Cole-Kripke / Sadeh style low-pass filtering over "activity counts"), rather than a
 * heavy neural network. Research on consumer-grade sleep staging shows deep models only
 * marginally beat these simple filters while costing far more battery — not worth it for
 * a phone laid on a shared mattress, which is already a noisier signal than a wrist band.
 *
 * The phone is not alone on the mattress. A partner rolling over produces a short,
 * high-magnitude spike that returns to baseline within one epoch. The wearer stirring out
 * of deep sleep produces a smaller but *sustained* rise across several consecutive epochs.
 * [minConsecutiveEpochsForStirring] is the filter that tells those two apart: a single
 * spike is ignored, a sustained rise is treated as a real light-sleep signal.
 */
class MovementScorer(
    private val epochDurationMs: Long = EPOCH_DURATION_MS,
    private val baselineAlpha: Double = 0.12,
    private val stirringMultiplier: Double = 1.8,
    private val activeMultiplier: Double = 4.0,
    private val minConsecutiveEpochsForStirring: Int = 2,
    private val minConsecutiveEpochsForActive: Int = 4
) {
    enum class MovementState { QUIET, STIRRING, ACTIVE }

    data class EpochResult(
        val score: Double,
        val baseline: Double,
        val state: MovementState,
        val consecutiveElevated: Int
    )

    private var epochStartMs = -1L
    private var epochSum = 0.0
    private var epochSampleCount = 0

    // Seeded low so the very first few epochs (while the sleeper is likely still awake
    // settling in) don't poison the "quiet" baseline too high.
    private var baseline = 0.35
    private var consecutiveElevated = 0
    private var warmedUp = false
    private var epochsSeen = 0

    private val noiseFloorG = 0.04 // ignore sensor jitter below this, in units of g

    /** Feed one raw accelerometer sample. Returns a completed [EpochResult] when an epoch closes. */
    fun onSample(timestampMs: Long, x: Float, y: Float, z: Float): EpochResult? {
        val g = SensorManager.GRAVITY_EARTH
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val deviation = abs(magnitude - g) / g
        val activity = if (deviation > noiseFloorG) deviation else 0.0

        if (epochStartMs < 0) epochStartMs = timestampMs
        epochSum += activity
        epochSampleCount += 1

        if (timestampMs - epochStartMs < epochDurationMs) return null

        val score = if (epochSampleCount > 0) epochSum / epochSampleCount else 0.0
        epochStartMs = timestampMs
        epochSum = 0.0
        epochSampleCount = 0
        epochsSeen += 1

        val stirThreshold = baseline * stirringMultiplier
        val activeThreshold = baseline * activeMultiplier
        val elevated = score > stirThreshold

        consecutiveElevated = if (elevated) consecutiveElevated + 1 else 0

        val state = when {
            score > activeThreshold && consecutiveElevated >= minConsecutiveEpochsForActive -> MovementState.ACTIVE
            elevated && consecutiveElevated >= minConsecutiveEpochsForStirring -> MovementState.STIRRING
            else -> MovementState.QUIET
        }

        // Only let quiet epochs pull the baseline down/up — an elevated epoch shouldn't
        // drag the "what counts as still" reference upward, or every night would need
        // more movement each time before triggering.
        if (!elevated) {
            baseline = if (!warmedUp && epochsSeen <= WARMUP_EPOCHS) {
                // Faster adaptation for the first few minutes to find a real baseline quickly.
                baseline * 0.5 + score * 0.5
            } else {
                warmedUp = true
                baseline * (1 - baselineAlpha) + score * baselineAlpha
            }
            baseline = baseline.coerceAtLeast(MIN_BASELINE)
        }

        return EpochResult(score, baseline, state, consecutiveElevated)
    }

    companion object {
        const val EPOCH_DURATION_MS = 30_000L
        private const val WARMUP_EPOCHS = 6
        private const val MIN_BASELINE = 0.02
    }
}
