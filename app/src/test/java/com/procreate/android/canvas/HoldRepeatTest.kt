package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldRepeatTest {

    @Test
    fun `an ordinary tap never becomes a repeat`() {
        // A two-finger tap is well under 300ms. If the first repeat fired sooner than that, a normal
        // undo tap would sometimes undo twice and quietly destroy work.
        assertTrue(
            "The first repeat must not fire within a normal tap",
            HoldRepeat.INITIAL_DELAY_MS >= 350L
        )
    }

    @Test
    fun `repeats accelerate`() {
        var previous = Long.MAX_VALUE
        for (i in 0..HoldRepeat.RAMP_STEPS) {
            val interval = HoldRepeat.intervalFor(i)
            assertTrue("Interval $i ($interval) must not be longer than the one before ($previous)", interval <= previous)
            previous = interval
        }
    }

    @Test
    fun `acceleration stops at the floor`() {
        assertEquals(HoldRepeat.MIN_INTERVAL_MS, HoldRepeat.intervalFor(HoldRepeat.RAMP_STEPS))
        assertEquals(HoldRepeat.MIN_INTERVAL_MS, HoldRepeat.intervalFor(HoldRepeat.RAMP_STEPS + 50))
        assertEquals(HoldRepeat.MIN_INTERVAL_MS, HoldRepeat.intervalFor(10_000))
    }

    @Test
    fun `it never runs away`() {
        // A floor that is too low outruns the user's eye: they release having undone far more than
        // they saw happen.
        assertTrue("12 steps a second is already brisk", HoldRepeat.MIN_INTERVAL_MS >= 60L)
    }

    @Test
    fun `a short hold steps a handful, not a dozen`() {
        // One second held: enough to correct a few strokes, not enough to lose a sketch.
        var elapsed = HoldRepeat.INITIAL_DELAY_MS
        var count = 0
        while (elapsed + HoldRepeat.intervalFor(count) <= 1000L) {
            elapsed += HoldRepeat.intervalFor(count)
            count++
        }
        assertTrue("A one-second hold should step a few times, was $count", count in 2..6)
    }

    @Test
    fun `a long hold travels properly`() {
        var elapsed = HoldRepeat.INITIAL_DELAY_MS
        var count = 0
        while (elapsed + HoldRepeat.intervalFor(count) <= 3000L) {
            elapsed += HoldRepeat.intervalFor(count)
            count++
        }
        assertTrue("Three seconds should cover real ground, was $count", count >= 20)
    }

    @Test
    fun `negative indices are treated as the first repeat`() {
        assertEquals(HoldRepeat.START_INTERVAL_MS, HoldRepeat.intervalFor(-5))
    }

    @Test
    fun `elapsed time grows with every repeat`() {
        var previous = 0L
        for (i in 0..20) {
            val elapsed = HoldRepeat.elapsedAfter(i)
            assertTrue("elapsed must increase", elapsed > previous)
            previous = elapsed
        }
    }
}
