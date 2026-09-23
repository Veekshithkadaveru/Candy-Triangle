package app.krafted.candytriangle.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injected clock is what makes the game loop's pacing testable at all: a hitch, a backwards
 * `nanoTime` and a resume from pause are otherwise only reachable on a device.
 */
class FrameClockTest {

    /** A hand-cranked `nanoTime`. */
    private class FakeClock(var nanos: Long = 0L) {
        fun advance(by: Long): FakeClock { nanos += by; return this }
        fun read(): Long = nanos
    }

    private val sixtyHz = 16_666_667L

    @Test
    fun `the first tick is zero, so no step runs on the frame that starts the loop`() {
        val clock = FakeClock(123_456_789L)
        val frameClock = FrameClock(clock::read)
        assertEquals(0L, frameClock.tick())
    }

    @Test
    fun `a normal delta passes through unchanged`() {
        val clock = FakeClock(1_000L)
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        clock.advance(sixtyHz)
        assertEquals(sixtyHz, frameClock.tick())
        clock.advance(sixtyHz)
        assertEquals(sixtyHz, frameClock.tick())
    }

    @Test
    fun `successive ticks do not accumulate drift`() {
        val clock = FakeClock()
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        var total = 0L
        repeat(120) {
            clock.advance(sixtyHz)
            total += frameClock.tick()
        }
        assertEquals(120 * sixtyHz, total)
    }

    @Test
    fun `a backwards clock yields zero and re-syncs`() {
        val clock = FakeClock(5_000_000L)
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        clock.nanos = 1_000_000L // the clock went backwards
        assertEquals(0L, frameClock.tick())
        // ...and the next tick is measured from the new reading, not the old one.
        clock.advance(sixtyHz)
        assertEquals(sixtyHz, frameClock.tick())
    }

    @Test
    fun `a five-second hitch clamps to MAX_FRAME_NANOS`() {
        val clock = FakeClock()
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        clock.advance(5_000_000_000L)
        assertEquals(FrameClock.MAX_FRAME_NANOS, frameClock.tick())
    }

    @Test
    fun `MAX_FRAME_NANOS is the engine's own 12-step cap`() {
        // FixedTimestep drops anything past 12 steps at 240 Hz (B1 note 11) = 50 ms, so clamping
        // here only means the loop and the engine agree on what was discarded.
        assertEquals(50_000_000L, FrameClock.MAX_FRAME_NANOS)
        assertEquals(12L, FrameClock.MAX_FRAME_NANOS * 240L / 1_000_000_000L)
    }

    @Test
    fun `exactly MAX_FRAME_NANOS is not clamped away`() {
        val clock = FakeClock()
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        clock.advance(FrameClock.MAX_FRAME_NANOS)
        assertEquals(FrameClock.MAX_FRAME_NANOS, frameClock.tick())
    }

    @Test
    fun `reset makes the next tick zero`() {
        val clock = FakeClock()
        val frameClock = FrameClock(clock::read)
        frameClock.tick()
        clock.advance(sixtyHz)
        assertEquals(sixtyHz, frameClock.tick())

        // A pause: seconds pass with the loop parked.
        frameClock.reset()
        clock.advance(8_000_000_000L)
        assertEquals("resume must not replay the pause", 0L, frameClock.tick())
        clock.advance(sixtyHz)
        assertEquals(sixtyHz, frameClock.tick())
    }

    @Test
    fun `lastTickNanos follows the clock`() {
        val clock = FakeClock(42L)
        val frameClock = FrameClock(clock::read)
        assertEquals(0L, frameClock.lastTickNanos)
        frameClock.tick()
        assertEquals(42L, frameClock.lastTickNanos)
    }

    @Test
    fun `the default clock is the system clock and ticks forward`() {
        val frameClock = FrameClock()
        frameClock.tick()
        var spins = 0
        var delta = frameClock.tick()
        while (delta == 0L && spins < 1_000_000) {
            delta = frameClock.tick()
            spins++
        }
        assertTrue("System.nanoTime never advanced", delta > 0L)
        assertTrue(delta <= FrameClock.MAX_FRAME_NANOS)
    }

    @Test
    fun `TARGET_FRAME_NANOS is the 60 fps budget`() {
        assertEquals(16_666_667L, FrameClock.TARGET_FRAME_NANOS)
        assertTrue(FrameClock.TARGET_FRAME_NANOS < FrameClock.MAX_FRAME_NANOS)
    }
}
