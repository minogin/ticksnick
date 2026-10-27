package com.ticksnick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a label is on something too small for the instrument to describe.
 *
 * It warns rather than stopping the run. The arithmetic decides who gets named, and it has a property no other check here has: **it works with no evidence at all**. An operation the
 * sampler never caught is still measurable, because seeing zero hits in any number of samples
 * bounds the whole occupancy at three ticks — the rule of three — however many samples were taken.
 */
class FloorCheckTest {

    private val step = 1_000_000.0    // a 1 ms tick, in nanoseconds

    /**
     * Never sampled once, forty million calls: the bound collapses to three ticks over the calls.
     *
     * This is why a zero-hit row is a finding rather than a blank.
     */
    @Test
    fun `the rule of three measures an operation that was never sampled`() {
        val upper = impliedUpperNanos(hits = 0, calls = 40_000_000, stepNanos = step)
        assertEquals(3 * step / 40_000_000, upper, 1e-9)
        assertTrue(upper < 0.1, "40M calls never sampled should bound under a tenth of a nanosecond")
        assertTrue(isTooSmall(0, 40_000_000, step), "a never-sampled hot label must be named")
    }

    /** No calls means no denominator, and no accusation. Not zero, not a divide by zero. */
    @Test
    fun `an operation that never ran is not accused`() {
        assertTrue(impliedUpperNanos(hits = 0, calls = 0, stepNanos = step).isNaN())
        assertFalse(isTooSmall(0, 0, step), "an operation that never ran was named")
    }

    /** The bound is an *upper* one: it must never come out below what was actually observed. */
    @Test
    fun `the bound is never below the implied duration`() {
        for (hits in listOf(1L, 10L, 307L, 4_862L, 118_116L)) {
            val calls = 6_286_329L
            val implied = hits * step / calls
            val upper = impliedUpperNanos(hits, calls, step)
            assertTrue(upper >= implied, "upper bound $upper below implied $implied at $hits hits")
        }
    }

    /** More evidence is a tighter bound. A check that loosened over a run could never settle. */
    @Test
    fun `the bound tightens as hits accumulate`() {
        val calls = 100_000_000L
        var previous = Double.MAX_VALUE
        for (hits in listOf(1L, 10L, 100L, 1_000L, 10_000L)) {
            // Hits scaled with calls held fixed is not a real run; what is asserted is the shape of
            // the confidence term, which is the part that decides how early the check may fire.
            val ratio = impliedUpperNanos(hits, calls, step) / (hits * step / calls)
            assertTrue(ratio < previous, "the confidence term did not shrink at $hits hits")
            previous = ratio
        }
    }

    /**
     * A label with 545 hits over 14,173,895 calls, 38.5 ns implied. Under the floor, and *not*
     * named — because the bound is 41.96 ns and the check inflates by
     * [Profile.FLOOR_BIAS_ALLOWANCE] before accusing anyone, which puts it at 50.35 against 50.
     *
     * **The margin is 0.35 ns**, and here the allowance alone decides the outcome. It is the kind
     * of boundary that moves under an innocent-looking edit, and the direction matters: the
     * allowance exists because the sampler reads short operations 5–9% low, so an operation made
     * to look smaller than it is must not be accused for it.
     */
    @Test
    fun `the bias allowance lets a borderline label go`() {
        val upper = impliedUpperNanos(hits = 545, calls = 14_173_895, stepNanos = step)
        assertEquals(41.96, upper, 0.1, "the upper bound moved")
        // Under the floor on its own, so only the allowance is keeping it off the list.
        assertTrue(upper < Profile.FLOOR_NANOS, "the test no longer exercises the allowance at all")
        assertTrue(upper * Profile.FLOOR_BIAS_ALLOWANCE > Profile.FLOOR_NANOS, "the margin has closed")
        assertFalse(isTooSmall(545, 14_173_895, step), "a label inside the allowance was accused")
    }

    /**
     * A label with 307 hits over 6,286,329 calls, 49.0 ns implied — also under the floor, and also
     * not named, but for the other reason. Its bound is 54.9 ns, already clear of
     * the floor before any allowance is applied, because 307 hits is thin evidence.
     *
     * Worth its own case because the two look identical in a report — both are sub-floor labels
     * that were let go — and they are let go by different halves of the check.
     */
    @Test
    fun `a thinly sampled label clears the floor on the bound alone`() {
        val upper = impliedUpperNanos(hits = 307, calls = 6_286_329, stepNanos = step)
        assertEquals(54.89, upper, 0.1, "the upper bound moved")
        assertTrue(upper > Profile.FLOOR_NANOS, "this case is supposed to clear without the allowance")
        assertFalse(isTooSmall(307, 6_286_329, step))
    }

    /**
     * An operation built to take 20 ns, on one machine at one thread and at eight. At one thread
     * it is named; at eight it is not, because eight busy threads make every operation 2.7× slower
     * and a 20 ns label reads 55 ns.
     *
     * The pair is why the check warns rather than fails: who gets named depends on the machine,
     * and that is a claim about the arithmetic, so it belongs in a test.
     */
    @Test
    fun `the same label is named or not depending on the machine`() {
        assertTrue(
            isTooSmall(hits = 559, calls = 31_497_063, stepNanos = step),
            "tinyStep at one thread bounds under 19.4 ns and must be named"
        )
        assertFalse(
            isTooSmall(hits = 4_862, calls = 87_847_316, stepNanos = step),
            "tinyStep at eight threads reads 55 ns and must not be named"
        )
    }
}
