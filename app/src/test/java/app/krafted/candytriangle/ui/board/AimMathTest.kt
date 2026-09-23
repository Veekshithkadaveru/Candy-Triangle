package app.krafted.candytriangle.ui.board

import app.krafted.candytriangle.engine.PhysicsParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * `AimMath` is the whole of the game's input. It must be **total**: there is no touch, however
 * absurd, that may produce a non-finite or out-of-range angle, because the result goes straight
 * into `Launcher.aimRadians` and from there into a launch velocity.
 */
class AimMathTest {

    private val clamp = PhysicsParams.DEFAULT.aimClampRadians // 70 degrees
    private val pivotX = 500f
    private val pivotY = 0f

    private fun aim(touchX: Float, touchY: Float, clampRadians: Float = clamp): Float =
        AimMath.aimFor(pivotX, pivotY, touchX, touchY, clampRadians)

    // -- the convention ---------------------------------------------------------------------------

    @Test
    fun `the pivot itself aims dead centre`() {
        assertEquals(0f, aim(pivotX, pivotY), 0f)
    }

    @Test
    fun `straight below the pivot is zero`() {
        assertEquals(0f, aim(pivotX, pivotY + 1f), 0f)
        assertEquals(0f, aim(pivotX, pivotY + 1250f), 0f)
    }

    @Test
    fun `positive is toward plus x, matching PhysicsParams`() {
        assertTrue(aim(pivotX + 100f, pivotY + 100f) > 0f)
        assertTrue(aim(pivotX - 100f, pivotY + 100f) < 0f)
    }

    @Test
    fun `45 degrees down-right is a quarter turn eighth`() {
        assertEquals(Math.PI.toFloat() / 4f, aim(pivotX + 100f, pivotY + 100f), 1e-5f)
    }

    // -- symmetry ---------------------------------------------------------------------------------

    @Test
    fun `plus dx and minus dx are exactly antisymmetric`() {
        var dx = 1f
        while (dx <= 900f) {
            var dy = 1f
            while (dy <= 1400f) {
                val right = aim(pivotX + dx, pivotY + dy)
                val left = aim(pivotX - dx, pivotY + dy)
                assertEquals("dx=$dx dy=$dy", -left, right, 0f)
                dy *= 3f
            }
            dx *= 3f
        }
    }

    // -- clamping ---------------------------------------------------------------------------------

    @Test
    fun `saturates to exactly the clamp past 70 degrees`() {
        // 80 degrees from straight down.
        val radians = Math.toRadians(80.0)
        val far = 1000.0
        val x = pivotX + (far * Math.sin(radians)).toFloat()
        val y = pivotY + (far * Math.cos(radians)).toFloat()
        assertEquals(clamp, aim(x, y), 0f)
        assertEquals(-clamp, aim(pivotX - (x - pivotX), y), 0f)
    }

    @Test
    fun `a touch level with the pivot saturates`() {
        assertEquals(clamp, aim(pivotX + 500f, pivotY), 0f)
        assertEquals(-clamp, aim(pivotX - 500f, pivotY), 0f)
    }

    @Test
    fun `a touch above the pivot still clamps by sign`() {
        assertEquals(clamp, aim(pivotX + 10f, pivotY - 400f), 0f)
        assertEquals(-clamp, aim(pivotX - 10f, pivotY - 400f), 0f)
        // Straight above is the deterministic tie-break: atan2(+0, -1) = +PI -> +clamp.
        assertEquals(clamp, aim(pivotX, pivotY - 400f), 0f)
    }

    @Test
    fun `just inside the clamp is not saturated`() {
        val radians = clamp - 0.05f
        val x = pivotX + (1000.0 * Math.sin(radians.toDouble())).toFloat()
        val y = pivotY + (1000.0 * Math.cos(radians.toDouble())).toFloat()
        assertEquals(radians, aim(x, y), 1e-4f)
        assertTrue(abs(aim(x, y)) < clamp)
    }

    // -- totality ---------------------------------------------------------------------------------

    @Test
    fun `NaN and infinite touches aim dead centre`() {
        assertEquals(0f, aim(Float.NaN, 100f), 0f)
        assertEquals(0f, aim(100f, Float.NaN), 0f)
        assertEquals(0f, aim(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, aim(Float.NEGATIVE_INFINITY, 100f), 0f)
        assertEquals(0f, AimMath.aimFor(Float.NaN, Float.NaN, 0f, 0f, clamp), 0f)
    }

    @Test
    fun `a NaN clamp cannot let an unclamped angle through`() {
        assertEquals(0f, aim(pivotX + 900f, pivotY + 10f, Float.NaN), 0f)
        assertEquals(0f, aim(pivotX + 900f, pivotY + 10f, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `a negative clamp is taken as its magnitude`() {
        assertEquals(clamp, aim(pivotX + 900f, pivotY + 10f, -clamp), 0f)
    }

    @Test
    fun `every result over a thousand sampled touches is finite and within the clamp`() {
        val random = Random(0x0D1A1)
        repeat(1000) {
            val x = random.nextFloat() * 4000f - 1500f
            val y = random.nextFloat() * 4000f - 1500f
            val result = aim(x, y)
            assertTrue("non-finite for ($x, $y): $result", result.isFinite())
            assertTrue("out of clamp for ($x, $y): $result", abs(result) <= clamp)
        }
    }

    @Test
    fun `the result always survives Launcher's own clamp unchanged`() {
        val params = PhysicsParams.DEFAULT
        val random = Random(0xBEEF)
        repeat(1000) {
            val result = aim(random.nextFloat() * 3000f - 1000f, random.nextFloat() * 3000f - 1000f)
            assertEquals(result, params.clampAim(result), 0f)
        }
    }

    // -- the screen-space composition ---------------------------------------------------------------

    @Test
    fun `aimForScreen agrees with aimFor through the transform`() {
        val t = BoardTransform.fit(1080f, 2400f, 1000f, 1346f, insetTopPx = 200f)
        val touchX = 820f
        val touchY = 1500f
        val expected = AimMath.aimFor(pivotX, pivotY, t.bx(touchX), t.by(touchY), clamp)
        assertEquals(expected, AimMath.aimForScreen(t, pivotX, pivotY, touchX, touchY, clamp), 0f)
    }

    @Test
    fun `a screen touch below the apex on the axis aims dead centre`() {
        val t = BoardTransform.fit(1080f, 2400f, 1000f, 1346f)
        val axisScreenX = t.sx(500f)
        assertEquals(0f, AimMath.aimForScreen(t, pivotX, pivotY, axisScreenX, t.sy(900f), clamp), 1e-6f)
    }

    @Test
    fun `aimForScreen stays total against the degenerate pre-layout transform`() {
        // A touch handler can fire before the first surfaceChanged, when the fit is 0 x 0.
        val degenerate = BoardTransform.fit(0f, 0f, 1000f, 1346f)
        val result = AimMath.aimForScreen(degenerate, pivotX, pivotY, 540f, 1200f, clamp)
        assertTrue(result.isFinite())
        assertTrue(abs(result) <= clamp)
    }
}
