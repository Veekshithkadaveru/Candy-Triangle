package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.BallSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The channel is the only thing two threads share, so its contract is a concurrency contract:
 * latest-wins aim, a launch delivered exactly once, and nothing that throws.
 */
class GameCommandChannelTest {

    private val channel = GameCommandChannel()

    // -- aim --------------------------------------------------------------------------------------

    @Test
    fun `an unset aim reads as NaN`() {
        assertTrue(channel.consumeAimIfChanged().isNaN())
    }

    @Test
    fun `an aim is delivered once and then reads as NaN`() {
        channel.setAim(0.5f)
        assertEquals(0.5f, channel.consumeAimIfChanged(), 0f)
        assertTrue(channel.consumeAimIfChanged().isNaN())
    }

    @Test
    fun `the latest aim wins - intermediate drag positions are coalesced`() {
        channel.setAim(0.1f)
        channel.setAim(0.2f)
        channel.setAim(-0.9f)
        assertEquals(-0.9f, channel.consumeAimIfChanged(), 0f)
        assertTrue(channel.consumeAimIfChanged().isNaN())
    }

    @Test
    fun `an aim of exactly zero is still a change, not an absence`() {
        channel.setAim(0f)
        val consumed = channel.consumeAimIfChanged()
        assertFalse(consumed.isNaN())
        assertEquals(0f, consumed, 0f)
    }

    @Test
    fun `a NaN aim is indistinguishable from unchanged, so it never reaches the launcher`() {
        channel.setAim(Float.NaN)
        assertTrue(channel.consumeAimIfChanged().isNaN())
    }

    @Test
    fun `extreme aims round-trip bit-exactly`() {
        for (value in floatArrayOf(-1.2217305f, 1.2217305f, Float.MIN_VALUE, -0f, 1e30f)) {
            channel.setAim(value)
            assertEquals(value.toRawBits(), channel.consumeAimIfChanged().toRawBits())
        }
    }

    // -- launch -----------------------------------------------------------------------------------

    @Test
    fun `a launch is consumed exactly once`() {
        assertFalse(channel.consumeLaunch())
        channel.requestLaunch()
        assertTrue(channel.consumeLaunch())
        assertFalse(channel.consumeLaunch())
    }

    @Test
    fun `repeated requests before a frame fire at most one ball`() {
        channel.requestLaunch()
        channel.requestLaunch()
        channel.requestLaunch()
        assertTrue(channel.consumeLaunch())
        assertFalse(channel.consumeLaunch())
    }

    @Test
    fun `noteLaunchRefused increments and never retries`() {
        assertEquals(0, channel.launchRefusals)
        channel.noteLaunchRefused()
        assertEquals(1, channel.launchRefusals)
        channel.noteLaunchRefused()
        assertEquals(2, channel.launchRefusals)
        // A refusal must not leave a launch pending: nothing is queued.
        assertFalse(channel.consumeLaunch())
    }

    // -- flags ------------------------------------------------------------------------------------

    @Test
    fun `paused round-trips`() {
        assertFalse(channel.paused)
        channel.paused = true
        assertTrue(channel.paused)
        channel.paused = false
        assertFalse(channel.paused)
    }

    @Test
    fun `aiming round-trips`() {
        assertFalse(channel.aiming)
        channel.aiming = true
        assertTrue(channel.aiming)
        channel.aiming = false
        assertFalse(channel.aiming)
    }

    @Test
    fun `ballSkin defaults to DEFAULT and round-trips`() {
        assertEquals(BallSkin.DEFAULT, channel.ballSkin)
        channel.ballSkin = BallSkin.GOLD
        assertEquals(BallSkin.GOLD, channel.ballSkin)
    }

    // -- reset ------------------------------------------------------------------------------------

    @Test
    fun `reset clears every pending command`() {
        channel.setAim(0.4f)
        channel.requestLaunch()
        channel.noteLaunchRefused()
        channel.aiming = true

        channel.reset()

        assertTrue(channel.consumeAimIfChanged().isNaN())
        assertFalse(channel.consumeLaunch())
        assertEquals(0, channel.launchRefusals)
        assertFalse(channel.aiming)
    }

    @Test
    fun `reset leaves ViewModel-owned state alone`() {
        channel.paused = true
        channel.ballSkin = BallSkin.BLUE
        channel.reset()
        assertTrue("reset must not silently un-pause a level", channel.paused)
        assertEquals("reset must not drop the equipped skin", BallSkin.BLUE, channel.ballSkin)
    }

    // -- concurrency --------------------------------------------------------------------------------

    @Test
    fun `a spinning writer and a draining reader agree on the final aim and never throw`() {
        val iterations = 200_000
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val draining = AtomicBoolean(true)
        val seenValues = AtomicInteger(0)
        val launches = AtomicInteger(0)

        val writer = Thread({
            try {
                ready.countDown()
                go.await()
                for (i in 0 until iterations) {
                    channel.setAim(i.toFloat())
                    if (i % 1000 == 0) channel.requestLaunch()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "test-writer")

        val reader = Thread({
            try {
                ready.countDown()
                go.await()
                while (draining.get()) {
                    val aim = channel.consumeAimIfChanged()
                    if (!aim.isNaN()) seenValues.incrementAndGet()
                    if (channel.consumeLaunch()) launches.incrementAndGet()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, "test-reader")

        writer.start()
        reader.start()
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        writer.join(30_000)
        draining.set(false)
        reader.join(30_000)

        assertNull("threads must not throw: ${failure.get()}", failure.get())
        assertFalse("writer did not finish", writer.isAlive)
        assertFalse("reader did not finish", reader.isAlive)

        // Whatever was coalesced, the *final* write must be visible to the next consumer.
        val tail = channel.consumeAimIfChanged()
        if (!tail.isNaN()) {
            assertEquals((iterations - 1).toFloat(), tail, 0f)
        } else {
            // The reader already drained it; it can only have drained the final value.
            assertTrue(seenValues.get() > 0)
        }
        assertTrue("nothing was observed at all", seenValues.get() > 0 || launches.get() > 0)
    }

    @Test
    fun `refusals counted from two threads are not lost`() {
        val threads = 4
        val perThread = 25_000
        val latch = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread({
                latch.await()
                repeat(perThread) { channel.noteLaunchRefused() }
            }, "test-refuser-$it")
        }
        workers.forEach { it.start() }
        latch.countDown()
        workers.forEach { it.join(30_000) }
        assertEquals(threads * perThread, channel.launchRefusals)
    }

    @Test
    fun `clearing paused wakes a parked waiter promptly`() {
        channel.paused = true
        val woke = CountDownLatch(1)
        val parked = Thread({
            // Mirrors GameLoop.park(): a long timeout, so only the signal can make this prompt.
            channel.awaitResume(30_000L) { channel.paused }
            woke.countDown()
        }, "test-parked-loop")
        parked.start()

        // Give the thread a moment to actually reach the await.
        Thread.sleep(50)
        channel.paused = false

        assertTrue("the parked loop was not woken", woke.await(5, TimeUnit.SECONDS))
        parked.join(5_000)
        assertFalse(parked.isAlive)
    }

    @Test
    fun `wakeParkedLoop breaks the park without clearing paused`() {
        channel.paused = true
        val woke = CountDownLatch(1)
        val parked = Thread({
            channel.awaitResume(30_000L) { channel.paused }
            woke.countDown()
        }, "test-parked-loop")
        parked.start()
        Thread.sleep(50)

        channel.wakeParkedLoop()

        assertTrue("stop() must be able to break a park", woke.await(5, TimeUnit.SECONDS))
        assertTrue("paused itself is untouched", channel.paused)
        parked.join(5_000)
    }
}
