package app.krafted.candytriangle.ui.board

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType

/**
 * Every bitmap the frame draws, decoded once and pre-scaled to its on-screen size.
 *
 * ## Why this type exists at all
 *
 * Two hard constraints from the plan meet here.
 *
 * 1. **`draw()` must never decode and must never allocate.** A `BitmapFactory` call inside the
 *    60 Hz loop is a dropped frame; so is the GC that follows it. Everything is resolved in
 *    [prepare] and then only read.
 * 2. **The canvas is `lockHardwareCanvas()`, which ignores `BlurMaskFilter`** (risk R1). So every
 *    glow in the game is a cached `RadialGradient` bitmap built here, never a per-frame blur.
 *
 * ## Asset memory (§13, risk R9)
 *
 * Shipped sprites are 400 x 400 and backdrops 1080 x 1920. Decoded at full size that is ~10 MB of
 * sprites plus 8.3 MB of backdrop. Instead each sprite is decoded with
 * `inSampleSize = `[RenderMath.sampleSizeFor] against its on-screen size and then
 * `createScaledBitmap`d exactly onto it (ball ~35 px, candy ~48 px, gem ~65 px on a 1080-wide
 * surface), which puts the whole sprite set well under 500 KB. The backdrop is sub-sampled against
 * the surface, and **only the current world's backdrop is held**.
 *
 * **`inScaled = false` is mandatory.** These live in `res/drawable-nodpi/`; letting
 * `BitmapFactory` apply density scaling would silently resize them out from under
 * [RenderMath.targetPx] and every `dst` rect the renderer computes (risk R7).
 *
 * ## Threading
 *
 * Confined to the game thread, which is the one that calls [prepare] (idempotently, at the top of
 * each frame) and then draws. That confinement is deliberate: the main thread must never
 * [release] a bitmap the loop is mid-`drawBitmap` on. [release] is called from the view only after
 * the loop thread has joined.
 *
 * Owner: Agent 1 (`ui/board`).
 */
class SpriteCache {

    private val ballSprites = arrayOfNulls<Bitmap>(BallSkin.entries.size)
    private val candySprites = arrayOfNulls<Bitmap>(CandyColor.entries.size)
    private val gemSprites = arrayOfNulls<Bitmap>(GemType.entries.size)

    /** Glow bitmaps by packed ARGB tint. Small and fixed: 4 world tints + 7 gems + gold. */
    private val glows = HashMap<Int, Bitmap>(16)

    /** The current world's backdrop. Exactly one is ever held. */
    var backdrop: Bitmap? = null
        private set

    /** The centre-crop of [backdrop] that fills the surface undistorted. */
    val backdropSrc: Rect = Rect()

    private var preparedScale: Float = Float.NaN
    private var preparedWorld: Int = -1
    private var preparedSurfaceWidth: Int = -1
    private var preparedSurfaceHeight: Int = -1

    /** True once [prepare] has produced a usable set. [BoardRenderer] draws vectors regardless. */
    var isPrepared: Boolean = false
        private set

    private val cropScratch = IntArray(4)

    /**
     * Decodes (or re-decodes) everything for this [scale], [worldIndex] and surface size.
     *
     * Idempotent and cheap to call every frame: four comparisons when nothing has changed. That is
     * why it lives on the game thread — it means bitmaps are created *and* recycled by the single
     * thread that draws them, so a HUD inset change mid-level can never recycle a bitmap out from
     * under a `drawBitmap`.
     *
     * @param worldIndex §6.1 world 1..4. Anything else falls back to world 1's backdrop.
     */
    fun prepare(
        resources: Resources,
        scale: Float,
        worldIndex: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        ballDiameter: Float,
        candyDiameter: Float,
        gemDiameter: Float,
        pegTintArgb: Int,
    ) {
        if (isPrepared &&
            scale == preparedScale &&
            worldIndex == preparedWorld &&
            surfaceWidth == preparedSurfaceWidth &&
            surfaceHeight == preparedSurfaceHeight
        ) {
            return
        }

        release()

        preparedScale = scale
        preparedWorld = worldIndex
        preparedSurfaceWidth = surfaceWidth
        preparedSurfaceHeight = surfaceHeight

        val ballPx = RenderMath.targetPx(ballDiameter, scale)
        val candyPx = RenderMath.targetPx(candyDiameter, scale)
        val gemPx = RenderMath.targetPx(gemDiameter, scale)

        for (skin in BallSkin.entries) {
            ballSprites[skin.ordinal] = decodeScaled(resources, ballDrawable(skin), ballPx)
        }
        for (color in CandyColor.entries) {
            candySprites[color.ordinal] = decodeScaled(resources, candyDrawable(color), candyPx)
        }
        for (type in GemType.entries) {
            gemSprites[type.ordinal] = decodeScaled(resources, gemDrawable(type), gemPx)
        }

        prepareBackdrop(resources, worldIndex, surfaceWidth, surfaceHeight)

        // Every tint the frame can ask for, built up front: the world's pegs, the seven gems and
        // the cup's gold rim. draw() must never build one.
        buildGlow(pegTintArgb)
        for (type in GemType.entries) buildGlow(gemGlowArgb(type))
        buildGlow(GOLD_GLOW_ARGB)

        isPrepared = true
    }

    fun ball(skin: BallSkin): Bitmap? = ballSprites[skin.ordinal]

    fun candy(color: CandyColor): Bitmap? = candySprites[color.ordinal]

    fun gem(type: GemType): Bitmap? = gemSprites[type.ordinal]

    /** The cached glow for a tint, or null if [prepare] was not asked to build one. */
    fun glow(argb: Int): Bitmap? = glows[argb]

    /** Recycles everything. Called from `detach()` / `onDetachedFromWindow()`, after the join. */
    fun release() {
        for (i in ballSprites.indices) {
            ballSprites[i]?.recycle()
            ballSprites[i] = null
        }
        for (i in candySprites.indices) {
            candySprites[i]?.recycle()
            candySprites[i] = null
        }
        for (i in gemSprites.indices) {
            gemSprites[i]?.recycle()
            gemSprites[i] = null
        }
        for (bitmap in glows.values) bitmap.recycle()
        glows.clear()
        backdrop?.recycle()
        backdrop = null
        backdropSrc.setEmpty()
        isPrepared = false
        preparedScale = Float.NaN
        preparedWorld = -1
        preparedSurfaceWidth = -1
        preparedSurfaceHeight = -1
    }

    // -- decoding ---------------------------------------------------------------------------------

    private fun prepareBackdrop(
        resources: Resources,
        worldIndex: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ) {
        val resId = backdropDrawable(worldIndex)
        val bounds = decodeBounds(resources, resId) ?: return
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Mandatory: drawable-nodpi art must not be density-scaled (risk R7).
            inScaled = false
            inSampleSize = maxOf(
                RenderMath.sampleSizeFor(bounds.outWidth, surfaceWidth.coerceAtLeast(1)),
                RenderMath.sampleSizeFor(bounds.outHeight, surfaceHeight.coerceAtLeast(1)),
            ).coerceAtLeast(1)
        }
        val decoded = runCatching { BitmapFactory.decodeResource(resources, resId, options) }
            .getOrNull()
        if (decoded == null) {
            Log.w(TAG, "backdrop $resId failed to decode; the scrimmed void stands in for it")
            return
        }
        backdrop = decoded
        RenderMath.centreCrop(
            srcWidth = decoded.width,
            srcHeight = decoded.height,
            dstWidth = surfaceWidth,
            dstHeight = surfaceHeight,
            out = cropScratch,
        )
        backdropSrc.set(cropScratch[0], cropScratch[1], cropScratch[2], cropScratch[3])
    }

    private fun decodeScaled(resources: Resources, resId: Int, targetPx: Int): Bitmap? {
        val bounds = decodeBounds(resources, resId) ?: return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inSampleSize = RenderMath.sampleSizeFor(bounds.outWidth, targetPx)
        }
        val decoded = runCatching { BitmapFactory.decodeResource(resources, resId, options) }
            .getOrNull()
        if (decoded == null) {
            Log.w(TAG, "sprite $resId failed to decode")
            return null
        }
        if (decoded.width == targetPx && decoded.height == targetPx) return decoded

        val scaled = runCatching {
            Bitmap.createScaledBitmap(decoded, targetPx, targetPx, true)
        }.getOrNull() ?: return decoded
        // createScaledBitmap may hand back the same instance; only recycle a real intermediate.
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun decodeBounds(resources: Resources, resId: Int): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        runCatching { BitmapFactory.decodeResource(resources, resId, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Log.w(TAG, "resource $resId has no decodable bounds")
            return null
        }
        return options
    }

    // -- glows ------------------------------------------------------------------------------------

    /**
     * One 64 x 64 radial gradient per tint: opaque at the centre, transparent at the rim.
     *
     * Stretched to whatever radius the frame needs. This is the whole answer to `lockHardwareCanvas`
     * dropping `BlurMaskFilter` (risk R1) — a naive glow would render as a hard-edged circle.
     */
    private fun buildGlow(argb: Int) {
        if (glows.containsKey(argb)) return
        val bitmap = runCatching {
            Bitmap.createBitmap(GLOW_PX, GLOW_PX, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return
        val radius = GLOW_PX * 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                radius,
                radius,
                radius,
                intArrayOf(
                    withAlpha(argb, 0xFF),
                    withAlpha(argb, 0x8C),
                    withAlpha(argb, 0x00),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawCircle(radius, radius, radius, paint)
        glows[argb] = bitmap
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

    // -- resource lookup --------------------------------------------------------------------------
    //
    // A `when` over the enum rather than Resources.getIdentifier: the sprite names in GameEnums
    // (`can_1`, `x10_cyan`, `ball_gold`, ...) are then checked by the compiler, and a renamed or
    // deleted drawable is a build failure instead of a silent 0 at runtime. A1 deviation note 3
    // renamed the backdrops to `world_1`..`world_4` (a resource name cannot start with a digit).

    private fun ballDrawable(skin: BallSkin): Int = when (skin) {
        BallSkin.DEFAULT -> R.drawable.ball
        BallSkin.GREEN -> R.drawable.ball_green
        BallSkin.PURPLE -> R.drawable.ball_purple
        BallSkin.BLUE -> R.drawable.ball_blue
        BallSkin.GOLD -> R.drawable.ball_gold
    }

    private fun candyDrawable(color: CandyColor): Int = when (color) {
        CandyColor.GREEN -> R.drawable.can_1
        CandyColor.PURPLE -> R.drawable.can_2
        CandyColor.PINK -> R.drawable.can_3
        CandyColor.BLUE -> R.drawable.can_4
    }

    private fun gemDrawable(type: GemType): Int = when (type) {
        GemType.SWEET -> R.drawable.x5
        GemType.BLAST -> R.drawable.x10_1
        GemType.LINE -> R.drawable.x10_cyan
        GemType.SPLIT -> R.drawable.x15
        GemType.EXTRA_BALL -> R.drawable.x25
        GemType.MAGNET -> R.drawable.x35
        GemType.SUGAR_STORM -> R.drawable.x80
    }

    private fun backdropDrawable(worldIndex: Int): Int = when (worldIndex) {
        2 -> R.drawable.world_2
        3 -> R.drawable.world_3
        4 -> R.drawable.world_4
        else -> R.drawable.world_1
    }

    companion object {

        private const val TAG = "SpriteCache"

        /** Glow source size. Cheap (16 KB each) and always upscaled, so 64 is plenty. */
        const val GLOW_PX: Int = 64

        /** `CupRimGold` from `ui/theme/Color.kt`, as the raw ARGB this `android.graphics` path needs. */
        const val GOLD_GLOW_ARGB: Int = 0xFFFFC23F.toInt()

        /** §4.2 gem colours (`GemSweet`..`GemSugarStorm` in `ui/theme/Color.kt`). */
        fun gemGlowArgb(type: GemType): Int = when (type) {
            GemType.SWEET -> 0xFFB347E8.toInt()
            GemType.BLAST -> 0xFFFFA52B.toInt()
            GemType.LINE -> 0xFF14D8EB.toInt()
            GemType.SPLIT -> 0xFFFF4030.toInt()
            GemType.EXTRA_BALL -> 0xFF2BE05C.toInt()
            GemType.MAGNET -> 0xFF2E6BFF.toInt()
            GemType.SUGAR_STORM -> 0xFFFF1F6B.toInt()
        }
    }
}
