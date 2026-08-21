package com.reveil.aube.tracking

import com.reveil.aube.tracking.MovementScorer.MovementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the actigraphy-style state machine: quiet baseline tracking, the "single spike vs
 * sustained rise" distinction, and the QUIET -> STIRRING -> ACTIVE consecutive-epoch ladder.
 */
class MovementScorerTest {

    private val g = 9.80665f
    private val epochMs = 1_000L

    private fun scorer() = MovementScorer(epochDurationMs = epochMs)

    /** Feeds one full epoch of identical samples and returns the closed-epoch result. */
    private fun MovementScorer.feedEpoch(startMs: Long, x: Float, y: Float, z: Float): MovementScorer.EpochResult {
        onSample(startMs, x, y, z)
        return onSample(startMs + epochMs, x, y, z)
            ?: error("expected epoch to close at $startMs")
    }

    @Test
    fun `onSample returns null until the epoch duration elapses`() {
        val scorer = scorer()
        assertNull(scorer.onSample(0L, 0f, 0f, g))
        assertNull(scorer.onSample(epochMs / 2, 0f, 0f, g))
    }

    @Test
    fun `still phone stays QUIET`() {
        val scorer = scorer()
        val result = scorer.feedEpoch(0L, 0f, 0f, g)
        assertEquals(MovementState.QUIET, result.state)
        assertEquals(0, result.consecutiveElevated)
    }

    @Test
    fun `a single spike epoch does not trigger STIRRING`() {
        val scorer = scorer()
        val spike = scorer.feedEpoch(0L, 0f, 0f, g * 3f)
        assertEquals(1, spike.consecutiveElevated)
        assertEquals(MovementState.QUIET, spike.state)

        // Returning to baseline immediately after resets the streak.
        val after = scorer.feedEpoch(epochMs, 0f, 0f, g)
        assertEquals(0, after.consecutiveElevated)
        assertEquals(MovementState.QUIET, after.state)
    }

    @Test
    fun `sustained elevated motion climbs QUIET then STIRRING then ACTIVE`() {
        val scorer = scorer()
        val states = (0 until 5).map { i ->
            scorer.feedEpoch(i * epochMs, 0f, 0f, g * 3f).state
        }
        // epoch1: consecutive=1 (below stirring threshold of 2) -> QUIET
        // epoch2-3: consecutive=2,3 -> STIRRING
        // epoch4-5: consecutive=4,5 and score above active threshold -> ACTIVE
        assertEquals(
            listOf(
                MovementState.QUIET,
                MovementState.STIRRING,
                MovementState.STIRRING,
                MovementState.ACTIVE,
                MovementState.ACTIVE
            ),
            states
        )
    }

    @Test
    fun `sensor jitter below the noise floor never elevates the score`() {
        val scorer = scorer()
        // 0.01g of jitter around gravity is below the 0.04g noise floor.
        val result = scorer.feedEpoch(0L, 0f, 0f, g + 0.01f * g)
        assertEquals(0.0, result.score, 1e-9)
        assertEquals(MovementState.QUIET, result.state)
    }

    @Test
    fun `elevated epochs do not drag the baseline upward`() {
        val scorer = scorer()
        val baselineBefore = scorer.feedEpoch(0L, 0f, 0f, g).baseline
        val duringSpike = scorer.feedEpoch(epochMs, 0f, 0f, g * 5f)
        // Baseline is only updated on non-elevated epochs, so it must not have moved.
        assertEquals(baselineBefore, duringSpike.baseline, 1e-9)
    }
}
