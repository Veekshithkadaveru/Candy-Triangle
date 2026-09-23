package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.level.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Everything the renderer computes, verified without a `Canvas`.
 *
 * The important one is [`barrelTip points down at aim zero and right-and-down at plus seventy`] and
 * its partner [`canvasRotationDegrees agrees with barrelTip`]: the launcher rotation sign is the
 * classic y-down-coordinates bug, and this project has no emulator to eyeball it on.
 */
class RenderMathTest {

    private val out2 = FloatArray(2)
    private val out8 = FloatArray(8)
    private val out4 = IntArray(4)

    // -- lerp -------------------------------------------------------------------------------------

    @Test
    fun `lerp is exact at the ends and midpoint`() {
        assertEquals(10f, RenderMath.lerp(10f, 20f, 0f), 0f)
        assertEquals(15f, RenderMath.lerp(10f, 20f, 0.5f), 0f)
        assertEquals(20f, RenderMath.lerp(10f, 20f, 1f), 0f)
    }

    @Test
    fun `lerp just below one lands on the endpoint without overshooting`() {
        // interpolationAlpha is [0, 1), so this is the largest value the renderer can ever see.
        // In float it rounds all the way to the endpoint — which is fine, and the point of the
        // test is the other half: it must never go *past* it and draw a ball ahead of the physics.
        val alpha = 1f - 5.9604645e-8f // 1 - 2^-24
        val value = RenderMath.lerp(100f, 200f, alpha)
        assertTrue("overshot the endpoint: $value", value <= 200f)
        assertEquals(200f, value, 1e-3f)
        assertTrue(value > RenderMath.lerp(100f, 200f, 0.5f))
    }

    @Test
    fun `lerp handles a negative span`() {
        assertEquals(-5f, RenderMath.lerp(5f, -15f, 0.5f), 1e-6f)
    }

    // -- centre crop --------------------------------------------------------------------------------

    @Test
    fun `centreCrop on a taller-than-source surface trims the sides`() {
        // A 1080x1920 backdrop (0.5625) on a 1080x2400 surface (0.45): the source is relatively
        // wider, so its full height is kept and the sides are trimmed.
        RenderMath.centreCrop(1080, 1920, 1080, 2400, out4)
        assertEquals(1920, out4[3] - out4[1])
        assertTrue(out4[2] - out4[0] < 1080)
        assertAspect(out4, 1080f / 2400f)
        assertInBounds(out4, 1080, 1920)
        assertCentred(out4, 1080, 1920)
    }

    @Test
    fun `centreCrop on a wider-than-source surface trims top and bottom`() {
        RenderMath.centreCrop(1080, 1920, 2400, 1080, out4)
        assertEquals(1080, out4[2] - out4[0])
        assertTrue(out4[3] - out4[1] < 1920)
        assertAspect(out4, 2400f / 1080f)
        assertInBounds(out4, 1080, 1920)
        assertCentred(out4, 1080, 1920)
    }

    @Test
    fun `centreCrop of a matching aspect keeps the whole source`() {
        RenderMath.centreCrop(1080, 1920, 540, 960, out4)
        assertEquals(0, out4[0])
        assertEquals(0, out4[1])
        assertEquals(1080, out4[2])
        assertEquals(1920, out4[3])
    }

    @Test
    fun `centreCrop never exceeds the source, over a sweep of surfaces`() {
        var w = 200
        while (w <= 3000) {
            var h = 200
            while (h <= 3000) {
                RenderMath.centreCrop(1080, 1920, w, h, out4)
                assertInBounds(out4, 1080, 1920)
                assertTrue("empty crop at $w x $h", out4[2] > out4[0] && out4[3] > out4[1])
                h += 437
            }
            w += 391
        }
    }

    @Test
    fun `centreCrop degrades rather than dividing by zero`() {
        RenderMath.centreCrop(0, 0, 1080, 2400, out4)
        assertEquals(0, out4[2] - out4[0])
        RenderMath.centreCrop(1080, 1920, 0, 0, out4)
        assertEquals(1080, out4[2])
        assertEquals(1920, out4[3])
    }

    // -- sprite sizing ------------------------------------------------------------------------------

    @Test
    fun `targetPx is never zero`() {
        assertTrue(RenderMath.targetPx(32f, 1.08f) > 0)
        assertEquals(1, RenderMath.targetPx(0f, 1.08f))
        assertEquals(1, RenderMath.targetPx(-32f, 1.08f))
        assertEquals(1, RenderMath.targetPx(32f, 0f))
        assertEquals(1, RenderMath.targetPx(32f, BoardTransform.MIN_SCALE))
        assertEquals(1, RenderMath.targetPx(Float.NaN, 1f))
        assertEquals(1, RenderMath.targetPx(32f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `targetPx matches the plan's on-screen sizes at 1080 wide`() {
        val scale = 1080f / 1000f
        val physics = GameConfig.DEFAULTS.physics
        assertEquals(35, RenderMath.targetPx(physics.ballRadius * 2f, scale))   // 32u -> ~35 px
        assertEquals(48, RenderMath.targetPx(physics.candyRadius * 2f, scale))  // 44u -> ~48 px
        assertEquals(65, RenderMath.targetPx(physics.gemRadius * 2f, scale))    // 60u -> ~65 px
    }

    @Test
    fun `sampleSizeFor picks the largest power of two that still covers the target`() {
        assertEquals(8, RenderMath.sampleSizeFor(400, 48))  // 400/8 = 50 >= 48, 400/16 = 25 < 48
        assertEquals(1, RenderMath.sampleSizeFor(400, 400))
        assertEquals(1, RenderMath.sampleSizeFor(400, 500))
        assertEquals(2, RenderMath.sampleSizeFor(400, 200))
        assertEquals(4, RenderMath.sampleSizeFor(1080, 270))
    }

    @Test
    fun `sampleSizeFor never sub-samples below the target and never returns zero`() {
        for (src in intArrayOf(64, 400, 1080, 1920, 2732)) {
            for (target in 1..400) {
                val sample = RenderMath.sampleSizeFor(src, target)
                assertTrue("sample $sample", sample >= 1)
                assertTrue("not a power of two: $sample", sample and (sample - 1) == 0)
                // Sub-sampling may never take the decode below the on-screen size — unless the
                // source was already smaller, in which case createScaledBitmap upscales instead.
                if (src >= target && sample < RenderMath.MAX_SAMPLE_SIZE) {
                    assertTrue("src=$src target=$target sample=$sample", src / sample >= target)
                }
                if (src < target) {
                    assertEquals("a small source must not be sub-sampled at all", 1, sample)
                }
            }
        }
        assertEquals(1, RenderMath.sampleSizeFor(0, 48))
        assertEquals(1, RenderMath.sampleSizeFor(400, 0))
    }

    // -- the launcher rotation sign -----------------------------------------------------------------

    @Test
    fun `barrelTip points down at aim zero and right-and-down at plus seventy`() {
        val pivotX = 500f
        val pivotY = 0f
        val length = 85f

        RenderMath.barrelTip(pivotX, pivotY, 0f, length, out2)
        assertEquals("aim 0 must point straight down (+y)", pivotX, out2[0], 1e-4f)
        assertEquals(pivotY + length, out2[1], 1e-4f)

        val seventy = Math.toRadians(70.0).toFloat()
        RenderMath.barrelTip(pivotX, pivotY, seventy, length, out2)
        assertTrue("+70 deg must point right of the pivot", out2[0] > pivotX)
        assertTrue("+70 deg must still point downward", out2[1] > pivotY)

        RenderMath.barrelTip(pivotX, pivotY, -seventy, length, out2)
        assertTrue("-70 deg must point left of the pivot", out2[0] < pivotX)
        assertTrue("-70 deg must still point downward", out2[1] > pivotY)
    }

    @Test
    fun `barrelTip keeps its distance from the pivot at every aim`() {
        val length = 85f
        var degrees = -70f
        while (degrees <= 70f) {
            RenderMath.barrelTip(500f, 0f, Math.toRadians(degrees.toDouble()).toFloat(), length, out2)
            val dx = out2[0] - 500f
            val dy = out2[1] - 0f
            assertEquals(length, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat(), 1e-3f)
            degrees += 5f
        }
    }

    @Test
    fun `barrelTip is total against a non-finite aim`() {
        RenderMath.barrelTip(500f, 0f, Float.NaN, 85f, out2)
        assertEquals(500f, out2[0], 0f)
        assertEquals(85f, out2[1], 0f)
    }

    @Test
    fun `canvasRotationDegrees agrees with barrelTip`() {
        // This is the actual sign check. Canvas.rotate applies [[cos, -sin], [sin, cos]], which in
        // a y-down space turns (0, +L) toward -x; canvasRotationDegrees negates to put it on +x.
        // rotateAboutPivot mirrors that matrix exactly, so if the two disagree the barrel points
        // the wrong way on a device and this fails here instead.
        val pivotX = 500f
        val pivotY = 0f
        val length = 85f
        var degrees = -70f
        while (degrees <= 70f) {
            val aim = Math.toRadians(degrees.toDouble()).toFloat()
            RenderMath.barrelTip(pivotX, pivotY, aim, length, out2)
            val expectedX = out2[0]
            val expectedY = out2[1]

            // The un-rotated barrel hangs straight down from the pivot.
            RenderMath.rotateAboutPivot(
                x = pivotX,
                y = pivotY + length,
                pivotX = pivotX,
                pivotY = pivotY,
                degrees = RenderMath.canvasRotationDegrees(aim),
                out = out2,
            )
            assertEquals("x at $degrees deg", expectedX, out2[0], 1e-3f)
            assertEquals("y at $degrees deg", expectedY, out2[1], 1e-3f)
            degrees += 5f
        }
    }

    @Test
    fun `canvasRotationDegrees negates the aim`() {
        assertEquals(0f, RenderMath.canvasRotationDegrees(0f), 0f)
        assertEquals(-70f, RenderMath.canvasRotationDegrees(Math.toRadians(70.0).toFloat()), 1e-3f)
        assertEquals(70f, RenderMath.canvasRotationDegrees(Math.toRadians(-70.0).toFloat()), 1e-3f)
        assertEquals(0f, RenderMath.canvasRotationDegrees(Float.NaN), 0f)
    }

    @Test
    fun `rotateAboutPivot is the identity at zero degrees`() {
        RenderMath.rotateAboutPivot(123f, 456f, 500f, 0f, 0f, out2)
        assertEquals(123f, out2[0], 1e-4f)
        assertEquals(456f, out2[1], 1e-4f)
    }

    // -- the candy cup ------------------------------------------------------------------------------

    @Test
    fun `the cup trapezoid is symmetric about cup x and its mouth spans exactly cup width`() {
        val cupConfig = GameConfig.DEFAULTS.cup
        val cupX = 372.5f
        val width = cupConfig.cupWidth // 180

        RenderMath.cupTrapezoid(cupX, width, cupConfig.laneYTop, cupConfig.laneYBottom, 0.72f, out8)

        // Top edge: the catch mouth, exactly cup.width wide and centred on cup.x — the same span
        // CandyCup.checkCatch tests, so what is drawn and what catches cannot drift apart.
        assertEquals(width, out8[2] - out8[0], 1e-4f)
        assertEquals(cupX, (out8[0] + out8[2]) * 0.5f, 1e-4f)
        assertEquals(cupX - width / 2f, out8[0], 1e-4f)
        assertEquals(cupX + width / 2f, out8[2], 1e-4f)

        // Bottom edge: narrower (the wrapper taper), still centred.
        assertEquals(cupX, (out8[4] + out8[6]) * 0.5f, 1e-4f)
        assertTrue(out8[4] - out8[6] < width)
        assertTrue(out8[4] - out8[6] > 0f)

        // The lane comes from config, not from CandyCup, which has no y.
        assertEquals(cupConfig.laneYTop, out8[1], 0f)
        assertEquals(cupConfig.laneYTop, out8[3], 0f)
        assertEquals(cupConfig.laneYBottom, out8[5], 0f)
        assertEquals(cupConfig.laneYBottom, out8[7], 0f)
        assertNotEquals(out8[1], out8[5])
    }

    @Test
    fun `the cup trapezoid tracks cup x across the whole base`() {
        val width = 180f
        for (cupX in floatArrayOf(90f, 500f, 910f)) {
            RenderMath.cupTrapezoid(cupX, width, 1280f, 1330f, 0.72f, out8)
            assertEquals(cupX, (out8[0] + out8[2]) * 0.5f, 1e-4f)
            assertEquals(width, out8[2] - out8[0], 1e-4f)
        }
    }

    @Test
    fun `an out-of-range taper is clamped, never inverting the trapezoid`() {
        RenderMath.cupTrapezoid(500f, 180f, 1280f, 1330f, -3f, out8)
        assertEquals(500f, out8[4], 1e-4f)
        assertEquals(500f, out8[6], 1e-4f)
        RenderMath.cupTrapezoid(500f, 180f, 1280f, 1330f, 5f, out8)
        assertEquals(180f, out8[4] - out8[6], 1e-4f)
    }

    // -- the aim guide --------------------------------------------------------------------------------

    @Test
    fun `aim dot alpha falls from the launcher to the first contact`() {
        val count = 40
        assertEquals(RenderMath.AIM_DOT_START_ALPHA, RenderMath.aimDotAlpha(0, count), 1e-6f)
        assertEquals(RenderMath.AIM_DOT_END_ALPHA, RenderMath.aimDotAlpha(count - 1, count), 1e-6f)

        var previous = Float.MAX_VALUE
        for (i in 0 until count) {
            val alpha = RenderMath.aimDotAlpha(i, count)
            assertTrue("alpha $alpha out of range", alpha in 0f..1f)
            assertTrue("alpha must not increase along the path", alpha <= previous + 1e-7f)
            previous = alpha
        }
    }

    @Test
    fun `aim dot alpha handles a one-point and an empty path`() {
        assertEquals(RenderMath.AIM_DOT_START_ALPHA, RenderMath.aimDotAlpha(0, 1), 0f)
        assertEquals(RenderMath.AIM_DOT_START_ALPHA, RenderMath.aimDotAlpha(0, 0), 0f)
    }

    @Test
    fun `aim dot alpha clamps an out-of-range index`() {
        assertEquals(RenderMath.AIM_DOT_END_ALPHA, RenderMath.aimDotAlpha(999, 10), 1e-6f)
        assertEquals(RenderMath.AIM_DOT_START_ALPHA, RenderMath.aimDotAlpha(-5, 10), 1e-6f)
    }

    @Test
    fun `alpha255 maps zero to one onto a Paint alpha`() {
        assertEquals(0, RenderMath.alpha255(0f))
        assertEquals(255, RenderMath.alpha255(1f))
        assertEquals(128, RenderMath.alpha255(0.5f))
        assertEquals(255, RenderMath.alpha255(3f))
        assertEquals(0, RenderMath.alpha255(-1f))
        assertEquals(0, RenderMath.alpha255(Float.NaN))
    }

    // -- helpers ----------------------------------------------------------------------------------

    private fun assertAspect(rect: IntArray, expected: Float) {
        val actual = (rect[2] - rect[0]).toFloat() / (rect[3] - rect[1])
        assertTrue("aspect $actual vs $expected", abs(actual - expected) < 0.01f)
    }

    private fun assertInBounds(rect: IntArray, srcW: Int, srcH: Int) {
        assertTrue("left ${rect[0]}", rect[0] >= 0)
        assertTrue("top ${rect[1]}", rect[1] >= 0)
        assertTrue("right ${rect[2]} > $srcW", rect[2] <= srcW)
        assertTrue("bottom ${rect[3]} > $srcH", rect[3] <= srcH)
    }

    private fun assertCentred(rect: IntArray, srcW: Int, srcH: Int) {
        assertTrue("horizontally off-centre", abs((srcW - rect[2]) - rect[0]) <= 1)
        assertTrue("vertically off-centre", abs((srcH - rect[3]) - rect[1]) <= 1)
    }
}
