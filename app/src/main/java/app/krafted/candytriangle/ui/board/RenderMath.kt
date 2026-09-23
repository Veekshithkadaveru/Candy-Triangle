package app.krafted.candytriangle.ui.board

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Every piece of arithmetic `BoardRenderer` and `SpriteCache` need, extracted so it is pure.
 *
 * This is the whole reason the render layer can be verified without an emulator: nothing here
 * touches `Canvas`, `Bitmap` or `Resources`, so a rotation sign, a centre-crop or a decode sample
 * size is a JUnit assertion instead of a screenshot. `BoardRenderer` and `SpriteCache` are left
 * thin enough to review by reading.
 *
 * Functions that produce more than one number write into a caller-owned array. That is not
 * micro-optimisation for its own sake: they run inside the 60 Hz draw, which must not allocate.
 *
 * Owner: Agent 1 (`ui/board`).
 */
object RenderMath {

    // -- interpolation ----------------------------------------------------------------------------

    /**
     * `a + (b - a) * t`, the form that is exact at `t = 0` and monotonic — the renderer draws every
     * ball at `lerp(prevX, x, world.interpolationAlpha)` so motion stays smooth on a display whose
     * refresh rate does not divide 240.
     */
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    // -- backdrop ---------------------------------------------------------------------------------

    /**
     * The largest centred sub-rectangle of a [srcWidth] x [srcHeight] source that has the
     * destination's aspect ratio — a centre-crop, so a 0.5625 backdrop fills an arbitrary portrait
     * surface without distortion and without letterbox bars.
     *
     * Writes `left, top, right, bottom` into [out] (length >= 4) and returns it, so the renderer
     * can keep one `Rect` alive for the life of the surface.
     */
    fun centreCrop(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        out: IntArray,
    ): IntArray {
        if (srcWidth <= 0 || srcHeight <= 0) {
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0
            return out
        }
        if (dstWidth <= 0 || dstHeight <= 0) {
            out[0] = 0; out[1] = 0; out[2] = srcWidth; out[3] = srcHeight
            return out
        }

        val srcAspect = srcWidth.toDouble() / srcHeight
        val dstAspect = dstWidth.toDouble() / dstHeight

        var cropW: Int
        var cropH: Int
        if (srcAspect > dstAspect) {
            // Source is relatively wider: keep its full height, trim the sides.
            cropH = srcHeight
            cropW = (srcHeight * dstAspect).roundToInt()
        } else {
            // Source is relatively taller: keep its full width, trim top and bottom.
            cropW = srcWidth
            cropH = (srcWidth / dstAspect).roundToInt()
        }
        cropW = cropW.coerceIn(1, srcWidth)
        cropH = cropH.coerceIn(1, srcHeight)

        val left = (srcWidth - cropW) / 2
        val top = (srcHeight - cropH) / 2
        out[0] = left
        out[1] = top
        out[2] = left + cropW
        out[3] = top + cropH
        return out
    }

    // -- sprite sizing ----------------------------------------------------------------------------

    /**
     * The on-screen pixel size a board-unit diameter needs, rounded up and never below 1.
     *
     * Sprites are pre-scaled to this once, in `SpriteCache.prepare`, so every `drawBitmap` in the
     * frame is roughly 1:1 and the shipped 400 x 400 art never sits in memory at full size (§13).
     */
    fun targetPx(boardDiameter: Float, scale: Float): Int {
        val px = boardDiameter * scale
        if (!px.isFinite() || px <= 0f) return 1
        return ceil(px.toDouble()).toInt().coerceAtLeast(1)
    }

    /**
     * The largest power-of-two `BitmapFactory.Options.inSampleSize` that still decodes at or above
     * [targetPx]. Sub-sampling is free at decode time; `createScaledBitmap` afterwards does the
     * last, non-power-of-two step.
     */
    fun sampleSizeFor(srcPx: Int, targetPx: Int): Int {
        if (srcPx <= 0 || targetPx <= 0) return 1
        var sample = 1
        while (sample < MAX_SAMPLE_SIZE && srcPx / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    // -- launcher ---------------------------------------------------------------------------------

    /**
     * The tip of the launcher barrel in board units: [lengthBoardUnits] from the pivot along the
     * aim, in `PhysicsParams`' convention — 0 is straight down (+y), positive toward +x.
     *
     * Writes `x, y` into [out] (length >= 2). Paired with [canvasRotationDegrees], this is the
     * rotation-sign check: `RenderMathTest` asserts that rotating `(pivotX, pivotY + length)` by
     * [canvasRotationDegrees] lands exactly here, so the barrel cannot point the wrong way without
     * a JUnit failure. There is no device to eyeball it on.
     */
    fun barrelTip(
        pivotX: Float,
        pivotY: Float,
        aimRadians: Float,
        lengthBoardUnits: Float,
        out: FloatArray,
    ): FloatArray {
        val aim = if (aimRadians.isFinite()) aimRadians else 0f
        out[0] = pivotX + lengthBoardUnits * sin(aim)
        out[1] = pivotY + lengthBoardUnits * cos(aim)
        return out
    }

    /**
     * The degrees to hand `Canvas.rotate` so the barrel points along [aimRadians].
     *
     * Negative, and that is not a typo. Board space has y **down**, but `Canvas.rotate` applies the
     * textbook `[[cos, -sin], [sin, cos]]` matrix, which in a y-down space turns (0, +L) toward
     * **-x**. Negating puts it back on +x, matching the aim convention. See [rotateAboutPivot].
     */
    fun canvasRotationDegrees(aimRadians: Float): Float {
        val aim = if (aimRadians.isFinite()) aimRadians else 0f
        return (-aim * DEGREES_PER_RADIAN).toFloat()
    }

    /**
     * `android.graphics.Canvas.rotate(degrees, pivotX, pivotY)`'s matrix, in pure Kotlin.
     *
     * Exists so the sign convention above is *provable* on the JVM rather than asserted in a
     * comment. The renderer still calls the real `Canvas.rotate`; this mirrors it.
     */
    fun rotateAboutPivot(
        x: Float,
        y: Float,
        pivotX: Float,
        pivotY: Float,
        degrees: Float,
        out: FloatArray,
    ): FloatArray {
        val radians = degrees / DEGREES_PER_RADIAN
        val c = cos(radians)
        val s = sin(radians)
        val dx = (x - pivotX).toDouble()
        val dy = (y - pivotY).toDouble()
        out[0] = (pivotX + dx * c - dy * s).toFloat()
        out[1] = (pivotY + dx * s + dy * c).toFloat()
        return out
    }

    // -- candy cup --------------------------------------------------------------------------------

    /**
     * The four corners of the §9.2 cupcake wrapper, as `x0, y0, x1, y1, x2, y2, x3, y3` in [out]
     * (length >= 8): top-left, top-right, bottom-right, bottom-left.
     *
     * The **top** edge is the catch mouth and spans exactly `cupWidth`, centred on [cupX] — the
     * same span `CandyCup.checkCatch` tests — so the drawn cup and the catch it performs can never
     * drift apart. The bottom is narrowed by [bottomTaper] purely for the wrapper silhouette.
     */
    fun cupTrapezoid(
        cupX: Float,
        cupWidth: Float,
        laneYTop: Float,
        laneYBottom: Float,
        bottomTaper: Float,
        out: FloatArray,
    ): FloatArray {
        val half = cupWidth * 0.5f
        val bottomHalf = half * bottomTaper.coerceIn(0f, 1f)
        out[0] = cupX - half; out[1] = laneYTop
        out[2] = cupX + half; out[3] = laneYTop
        out[4] = cupX + bottomHalf; out[5] = laneYBottom
        out[6] = cupX - bottomHalf; out[7] = laneYBottom
        return out
    }

    // -- aim guide --------------------------------------------------------------------------------

    /**
     * Alpha for dot [index] of [count] along the aim guide: a linear falloff from
     * [startAlpha] at the launcher to [endAlpha] at the first contact, so the guide reads as a
     * direction rather than a wall of dots.
     */
    fun aimDotAlpha(
        index: Int,
        count: Int,
        startAlpha: Float = AIM_DOT_START_ALPHA,
        endAlpha: Float = AIM_DOT_END_ALPHA,
    ): Float {
        if (count <= 1) return startAlpha.coerceIn(0f, 1f)
        val t = (index.toFloat() / (count - 1)).coerceIn(0f, 1f)
        return lerp(startAlpha, endAlpha, t).coerceIn(0f, 1f)
    }

    /** A 0..1 alpha as the 0..255 a `Paint` takes. */
    fun alpha255(alpha: Float): Int {
        if (!alpha.isFinite()) return 0
        return (alpha * 255f).roundToInt().coerceIn(0, 255)
    }

    // -- constants --------------------------------------------------------------------------------

    /** Aim-guide dot alpha at the launcher end (plan: 0.9 -> 0.15). */
    const val AIM_DOT_START_ALPHA: Float = 0.9f

    /** Aim-guide dot alpha at the first contact. */
    const val AIM_DOT_END_ALPHA: Float = 0.15f

    /** Cap on [sampleSizeFor]: 64 already reduces a 400 px sprite to 6 px. */
    const val MAX_SAMPLE_SIZE: Int = 64

    private const val DEGREES_PER_RADIAN: Double = 180.0 / Math.PI
}
