package app.krafted.candytriangle.ui.board

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.level.BoardPoint
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.TrailType
import kotlin.math.sqrt

/**
 * Draws one frame of the board onto a hardware canvas, in §9.2's order.
 *
 * ## The two rules that shape every line below
 *
 * 1. **No allocation.** Paints, `Path`s, `Rect`s and scratch arrays are fields created once;
 *    `Path.rewind()` and `RectF.set()` reuse them. Every sweep over `world.balls`,
 *    `board.candies`, `board.layout.gems` and `world.pegs` is an **index loop** — an iterator or a
 *    `filter` here allocates 60 times a second, and the engine team made exactly this change for
 *    exactly this reason (B3 note 5).
 * 2. **No decoding.** Bitmaps come from [SpriteCache], which resolved them before the first frame.
 *    Where a sprite is missing the renderer falls back to a vector shape rather than skipping the
 *    entity, so a failed decode costs fidelity, not gameplay.
 *
 * Glow is never `BlurMaskFilter`: `lockHardwareCanvas()` ignores it (risk R1), so each glow is a
 * cached radial-gradient bitmap stretched to the radius the frame wants.
 *
 * Colours are raw ARGB ints, not `ui/theme/Color.kt`'s `Color` values — this is the
 * `android.graphics` path, and `ui/theme` belongs to nobody in D1. The values are copied from it
 * and named after it so the two can be diffed by eye.
 *
 * Confined to the game thread. Owner: Agent 1 (`ui/board`).
 */
class BoardRenderer {

    // -- long-lived drawing state (never allocated per frame) -------------------------------------

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val path = Path()
    private val rectF = RectF()
    private val ovalF = RectF()
    private val surfaceRect = Rect()

    private val cupCorners = FloatArray(8)
    private val point = FloatArray(2)

    /**
     * Draws the whole frame. [aimPath] is `Launcher.predictTrajectory`'s output, recomputed by the
     * loop only while aiming; [showAim] gates it so a stale path is never drawn after a launch.
     */
    fun draw(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
        worldTintArgb: Int,
        aimPath: List<BoardPoint>?,
        showAim: Boolean,
        trail: TrailType,
    ) {
        // 1. Clear. A hardware canvas has no partial damage, so the whole surface is redrawn and
        //    SRC (rather than SRC_OVER) avoids blending against the previous buffer.
        canvas.drawColor(NIGHT_VOID, PorterDuff.Mode.SRC)

        // 2. Backdrop, centre-cropped so a 0.5625 source fills a portrait surface undistorted.
        drawBackdrop(canvas, sprites)

        // 3. Scrim. 0x73 = 115/255 = 45%, exactly §9.1.
        canvas.drawColor(BACKDROP_SCRIM)

        // 4-11.
        drawTriangleFrame(canvas, board, transform, worldTintArgb)
        drawLauncher(canvas, board, transform, sprites)
        drawPegs(canvas, board, transform, sprites, worldTintArgb)
        drawCandies(canvas, board, transform, sprites)
        drawGems(canvas, board, transform, sprites)
        drawCup(canvas, board, transform, sprites)
        if (showAim) drawAimGuide(canvas, transform, aimPath)
        drawBalls(canvas, board, transform, sprites, trail)
    }

    // -- 2. backdrop ------------------------------------------------------------------------------

    private fun drawBackdrop(canvas: Canvas, sprites: SpriteCache) {
        val backdrop = sprites.backdrop ?: return
        if (backdrop.isRecycled || sprites.backdropSrc.isEmpty) return
        surfaceRect.set(0, 0, canvas.width, canvas.height)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(backdrop, sprites.backdropSrc, surfaceRect, bitmapPaint)
    }

    // -- 4. triangle frame ------------------------------------------------------------------------

    private fun drawTriangleFrame(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        worldTintArgb: Int,
    ) {
        val cfg = board.config.board
        path.rewind()
        path.moveTo(transform.sx(cfg.walls.left.x2), transform.sy(cfg.walls.left.y2))
        path.lineTo(transform.sx(cfg.apex.x), transform.sy(cfg.apex.y))
        path.lineTo(transform.sx(cfg.walls.right.x2), transform.sy(cfg.walls.right.y2))
        // The base stays open unless the config says otherwise — a ball leaves through it (§3.1).
        if (!cfg.baseOpen) path.close()

        strokePaint.color = worldTintArgb
        strokePaint.alpha = FRAME_HALO_ALPHA
        strokePaint.strokeWidth = transform.len(FRAME_HALO_WIDTH_U)
        canvas.drawPath(path, strokePaint)

        strokePaint.color = worldTintArgb
        strokePaint.alpha = 255
        strokePaint.strokeWidth = transform.len(FRAME_LINE_WIDTH_U)
        canvas.drawPath(path, strokePaint)
    }

    // -- 5. launcher (§9.2: cannon barrel + peppermint swirl pivot + gold collar) -------------------

    private fun drawLauncher(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
    ) {
        val pivot = board.config.board.launcher.pivot
        val px = transform.sx(pivot.x)
        val py = transform.sy(pivot.y)

        canvas.save()
        // Negative degrees: see RenderMath.canvasRotationDegrees. The sign is pinned by
        // RenderMathTest.barrelTip, which is the only way we can verify it without a device.
        canvas.rotate(RenderMath.canvasRotationDegrees(board.launcher.aimRadians), px, py)

        // Barrel: a rounded rect hanging from the pivot along +y (straight down at aim 0).
        val halfWidth = transform.len(BARREL_WIDTH_U * 0.5f)
        val corner = transform.len(BARREL_WIDTH_U * 0.35f)
        rectF.set(px - halfWidth, py, px + halfWidth, py + transform.len(BARREL_LENGTH_U))

        val barrelGlow = sprites.glow(SpriteCache.GOLD_GLOW_ARGB)
        if (barrelGlow != null && !barrelGlow.isRecycled) {
            val halo = transform.len(BARREL_GLOW_U)
            ovalF.set(rectF.left - halo, rectF.top - halo, rectF.right + halo, rectF.bottom + halo)
            glowPaint.alpha = BARREL_GLOW_ALPHA
            canvas.drawBitmap(barrelGlow, null, ovalF, glowPaint)
        }
        fillPaint.color = BARREL_BODY
        fillPaint.alpha = 255
        canvas.drawRoundRect(rectF, corner, corner, fillPaint)
        strokePaint.color = COLLAR_GOLD
        strokePaint.alpha = 255
        strokePaint.strokeWidth = transform.len(BARREL_EDGE_WIDTH_U)
        canvas.drawRoundRect(rectF, corner, corner, strokePaint)

        strokePaint.color = SWIRL_PINK
        strokePaint.alpha = BARREL_CORE_ALPHA
        strokePaint.strokeWidth = transform.len(BARREL_CORE_WIDTH_U)
        canvas.drawLine(px, py + transform.len(16f), px, py + transform.len(BARREL_LENGTH_U - 10f), strokePaint)

        // A filled receiver seats the peppermint wheel into the cannon body.
        fillPaint.color = BARREL_BODY
        fillPaint.alpha = 255
        canvas.drawCircle(px, py, transform.len(COLLAR_RADIUS_U), fillPaint)

        // Peppermint swirl: six alternating wedges, spinning with the aim.
        val swirlRadius = transform.len(SWIRL_RADIUS_U)
        ovalF.set(px - swirlRadius, py - swirlRadius, px + swirlRadius, py + swirlRadius)
        for (wedge in 0 until SWIRL_WEDGES) {
            fillPaint.color = if (wedge % 2 == 0) SWIRL_WHITE else SWIRL_PINK
            fillPaint.alpha = 255
            canvas.drawArc(
                ovalF,
                wedge * (360f / SWIRL_WEDGES),
                360f / SWIRL_WEDGES,
                true,
                fillPaint,
            )
        }

        // Gold collar around the swirl.
        strokePaint.color = COLLAR_GOLD
        strokePaint.alpha = 255
        strokePaint.strokeWidth = transform.len(COLLAR_WIDTH_U)
        canvas.drawCircle(px, py, transform.len(COLLAR_RADIUS_U), strokePaint)

        canvas.restore()
    }

    // -- 6. pegs ----------------------------------------------------------------------------------

    /**
     * Pegs carry no `prevX`, so — unlike balls — they are drawn at their post-step position with no
     * interpolation. At §3.3's 40 u amplitude and 3.0 s period the peak speed is 84 u/s, so at
     * 60 fps a moving peg lags its true position by under 0.6 u: sub-pixel on any real surface, and
     * not worth a second `prevX` field inside the 240 Hz step.
     */
    private fun drawPegs(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
        worldTintArgb: Int,
    ) {
        val pegs = board.world.pegs
        val glow = sprites.glow(worldTintArgb)
        val pegPx = transform.len(board.params.pegRadius)
        val glowPx = pegPx * PEG_GLOW_FACTOR

        for (i in pegs.indices) {
            val peg = pegs[i]
            if (!peg.active || peg.kind != ColliderKind.PEG) continue
            val cx = transform.sx(peg.x)
            val cy = transform.sy(peg.y)

            if (glow != null && !glow.isRecycled) {
                rectF.set(cx - glowPx, cy - glowPx, cx + glowPx, cy + glowPx)
                glowPaint.alpha = PEG_GLOW_ALPHA
                canvas.drawBitmap(glow, null, rectF, glowPaint)
            }

            fillPaint.color = worldTintArgb
            fillPaint.alpha = 255
            canvas.drawCircle(cx, cy, pegPx, fillPaint)

            fillPaint.color = ICING_WHITE
            fillPaint.alpha = 255
            canvas.drawCircle(cx, cy, pegPx * PEG_CORE_FACTOR, fillPaint)
        }
    }

    // -- 7. candies -------------------------------------------------------------------------------

    private fun drawCandies(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
    ) {
        val candies = board.candies
        // Candy has no radius field: the sensor radius is a config constant (§3.2).
        val half = transform.len(board.config.physics.candyRadius)

        for (i in candies.indices) {
            val candy = candies[i]
            if (!candy.active) continue
            val cx = transform.sx(candy.x)
            val cy = transform.sy(candy.y)
            rectF.set(cx - half, cy - half, cx + half, cy + half)

            val sprite = sprites.candy(candy.color)
            if (sprite != null && !sprite.isRecycled) {
                bitmapPaint.alpha = 255
                canvas.drawBitmap(sprite, null, rectF, bitmapPaint)
            } else {
                fillPaint.color = candyArgb(candy.color)
                fillPaint.alpha = 255
                canvas.drawCircle(cx, cy, half, fillPaint)
            }
        }
    }

    // -- 8. gems ----------------------------------------------------------------------------------

    private fun drawGems(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
    ) {
        val gems = board.layout.gems
        val half = transform.len(board.params.gemRadius)
        val glowPx = half * GEM_GLOW_FACTOR

        for (i in gems.indices) {
            val gem = gems[i]
            if (!gem.active) continue
            val cx = transform.sx(gem.x)
            val cy = transform.sy(gem.y)

            val tint = SpriteCache.gemGlowArgb(gem.type)
            val glow = sprites.glow(tint)
            if (glow != null && !glow.isRecycled) {
                rectF.set(cx - glowPx, cy - glowPx, cx + glowPx, cy + glowPx)
                glowPaint.alpha = GEM_GLOW_ALPHA
                canvas.drawBitmap(glow, null, rectF, glowPaint)
            }

            rectF.set(cx - half, cy - half, cx + half, cy + half)
            val sprite = sprites.gem(gem.type)
            if (sprite != null && !sprite.isRecycled) {
                bitmapPaint.alpha = 255
                canvas.drawBitmap(sprite, null, rectF, bitmapPaint)
            } else {
                fillPaint.color = tint
                fillPaint.alpha = 255
                canvas.drawCircle(cx, cy, half, fillPaint)
            }
        }
    }

    // -- 9. candy cup (§9.2: pleated cupcake wrapper + gold rim glow) -------------------------------

    private fun drawCup(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
    ) {
        val cupConfig = board.config.cup
        // CandyCup has no y: the lane is config.cup.laneYTop..laneYBottom, centred on cup.x.
        RenderMath.cupTrapezoid(
            cupX = board.cup.x,
            cupWidth = board.cup.width,
            laneYTop = cupConfig.laneYTop,
            laneYBottom = cupConfig.laneYBottom,
            bottomTaper = CUP_BOTTOM_TAPER,
            out = cupCorners,
        )

        val topLeftX = transform.sx(cupCorners[0])
        val topY = transform.sy(cupCorners[1])
        val topRightX = transform.sx(cupCorners[2])
        val bottomRightX = transform.sx(cupCorners[4])
        val bottomY = transform.sy(cupCorners[5])
        val bottomLeftX = transform.sx(cupCorners[6])

        path.rewind()
        path.moveTo(topLeftX, topY)
        path.lineTo(topRightX, topY)
        path.lineTo(bottomRightX, bottomY)
        path.lineTo(bottomLeftX, bottomY)
        path.close()

        fillPaint.color = CUP_WRAPPER
        fillPaint.alpha = 255
        canvas.drawPath(path, fillPaint)

        // Pleats: straight lines from the top edge to the correspondingly tapered bottom edge.
        strokePaint.color = CUP_PLEAT
        strokePaint.alpha = CUP_PLEAT_ALPHA
        strokePaint.strokeWidth = transform.len(CUP_PLEAT_WIDTH_U)
        for (i in 1 until CUP_PLEATS) {
            val t = i.toFloat() / CUP_PLEATS
            canvas.drawLine(
                RenderMath.lerp(topLeftX, topRightX, t),
                topY,
                RenderMath.lerp(bottomLeftX, bottomRightX, t),
                bottomY,
                strokePaint,
            )
        }

        // Gold rim: the catch mouth, spanning exactly cup.width — the span checkCatch tests.
        val glow = sprites.glow(SpriteCache.GOLD_GLOW_ARGB)
        if (glow != null && !glow.isRecycled) {
            val halo = transform.len(CUP_RIM_GLOW_U)
            rectF.set(topLeftX, topY - halo, topRightX, topY + halo)
            glowPaint.alpha = CUP_RIM_GLOW_ALPHA
            canvas.drawBitmap(glow, null, rectF, glowPaint)
        }
        strokePaint.color = CUP_RIM_GOLD
        strokePaint.alpha = 255
        strokePaint.strokeWidth = transform.len(CUP_RIM_WIDTH_U)
        canvas.drawLine(topLeftX, topY, topRightX, topY, strokePaint)

        // A narrow icing highlight makes the moving catch boundary readable over every world.
        strokePaint.color = ICING_WHITE
        strokePaint.alpha = CUP_RIM_HIGHLIGHT_ALPHA
        strokePaint.strokeWidth = transform.len(CUP_RIM_HIGHLIGHT_WIDTH_U)
        canvas.drawLine(topLeftX, topY, topRightX, topY, strokePaint)
    }

    // -- 10. aim guide ----------------------------------------------------------------------------

    private fun drawAimGuide(canvas: Canvas, transform: BoardTransform, aimPath: List<BoardPoint>?) {
        if (aimPath == null) return
        val count = aimPath.size
        if (count == 0) return

        val dotRadius = transform.len(AIM_DOT_RADIUS_U)
        fillPaint.color = ICING_WHITE
        for (i in 0 until count) {
            val p = aimPath[i]
            fillPaint.alpha = RenderMath.alpha255(RenderMath.aimDotAlpha(i, count))
            canvas.drawCircle(transform.sx(p.x), transform.sy(p.y), dotRadius, fillPaint)
        }

        // The last point is the first contact (B2's predictTrajectory stops there): ring it.
        val impact = aimPath[count - 1]
        strokePaint.color = ICING_WHITE
        strokePaint.alpha = AIM_IMPACT_ALPHA
        strokePaint.strokeWidth = transform.len(AIM_IMPACT_WIDTH_U)
        canvas.drawCircle(
            transform.sx(impact.x),
            transform.sy(impact.y),
            transform.len(AIM_IMPACT_RADIUS_U),
            strokePaint,
        )
    }

    // -- 11. balls --------------------------------------------------------------------------------

    private fun drawBalls(
        canvas: Canvas,
        board: LevelBoard,
        transform: BoardTransform,
        sprites: SpriteCache,
        trail: TrailType,
    ) {
        val balls = board.world.balls
        if (balls.isEmpty()) return
        val alpha = board.world.interpolationAlpha
        val half = transform.len(board.params.ballRadius)

        for (i in balls.indices) {
            val ball = balls[i]
            if (!ball.active) continue
            // Interpolated: the step runs at 240 Hz and the frame at 60, so a ball drawn at its
            // raw post-step position judders on any display whose rate does not divide 240.
            point[0] = RenderMath.lerp(ball.prevX, ball.x, alpha)
            point[1] = RenderMath.lerp(ball.prevY, ball.y, alpha)
            val cx = transform.sx(point[0])
            val cy = transform.sy(point[1])

            if (trail != TrailType.NONE) {
                drawTrail(canvas, transform, cx, cy, ball.vx, ball.vy, trail)
            }
            rectF.set(cx - half, cy - half, cx + half, cy + half)

            val sprite = sprites.ball(ball.skin)
            if (sprite != null && !sprite.isRecycled) {
                bitmapPaint.alpha = 255
                canvas.drawBitmap(sprite, null, rectF, bitmapPaint)
            } else {
                fillPaint.color = BALL_DEFAULT
                fillPaint.alpha = 255
                canvas.drawCircle(cx, cy, half, fillPaint)
            }
        }
    }

    /**
     * Draws a short velocity-aligned candy streak behind a ball. It needs no history buffer and
     * allocates nothing: speed chooses direction only, while the board-space ball radius fixes
     * the trail's visual length so a fast drop cannot paint across half the board.
     */
    private fun drawTrail(
        canvas: Canvas,
        transform: BoardTransform,
        cx: Float,
        cy: Float,
        vx: Float,
        vy: Float,
        trail: TrailType,
    ) {
        val speed = sqrt(vx * vx + vy * vy)
        if (speed < TRAIL_MIN_SPEED_U) return
        val dx = vx / speed
        val dy = vy / speed
        val spacing = transform.len(TRAIL_SPACING_U)
        val baseRadius = transform.len(TRAIL_RADIUS_U)
        fillPaint.color = trailArgb(trail)
        for (step in 1..TRAIL_DOTS) {
            val fade = TRAIL_DOTS - step + 1
            fillPaint.alpha = TRAIL_ALPHA * fade / TRAIL_DOTS
            canvas.drawCircle(
                cx - dx * spacing * step,
                cy - dy * spacing * step,
                baseRadius * fade / TRAIL_DOTS,
                fillPaint,
            )
        }
    }

    // -- fallback palette --------------------------------------------------------------------------

    private fun candyArgb(color: CandyColor): Int = when (color) {
        CandyColor.GREEN -> CANDY_GREEN
        CandyColor.PURPLE -> CANDY_PURPLE
        CandyColor.PINK -> CANDY_ROSE
        CandyColor.BLUE -> CANDY_BLUE
    }

    private fun trailArgb(trail: TrailType): Int = when (trail) {
        TrailType.NONE -> ICING_WHITE
        TrailType.GREEN -> CANDY_GREEN
        TrailType.PURPLE -> CANDY_PURPLE
        TrailType.PINK -> CANDY_ROSE
        TrailType.BLUE -> CANDY_BLUE
    }
}

// ---------------------------------------------------------------------------------------------
// Palette. Raw ARGB copies of `ui/theme/Color.kt` — that file belongs to nobody in D1, and this
// is the android.graphics path, which cannot take a Compose `Color` anyway.
// ---------------------------------------------------------------------------------------------

private const val NIGHT_VOID = 0xFF0B0518.toInt()
private const val BACKDROP_SCRIM = 0x73000000
private const val ICING_WHITE = 0xFFF5ECFF.toInt()
private const val BALL_DEFAULT = 0xFFFF2EAF.toInt()
private const val CANDY_GREEN = 0xFF3DE84B.toInt()
private const val CANDY_PURPLE = 0xFFB23CF0.toInt()
private const val CANDY_ROSE = 0xFFFF4FC8.toInt()
private const val CANDY_BLUE = 0xFF33A0FF.toInt()
private const val CUP_RIM_GOLD = 0xFFFFC23F.toInt()
private const val COLLAR_GOLD = 0xFFFFC23F.toInt()
private const val BARREL_BODY = 0xFF2A1148.toInt()
private const val SWIRL_WHITE = 0xFFF5ECFF.toInt()
private const val SWIRL_PINK = 0xFFFF4FC8.toInt()
private const val CUP_WRAPPER = 0xFFFF4FC8.toInt()
private const val CUP_PLEAT = 0xFF6B0046.toInt()

// -- geometry, in board units (§3.1), so every size scales with the letterbox ------------------

private const val FRAME_HALO_WIDTH_U = 14f
private const val FRAME_LINE_WIDTH_U = 4f
private const val FRAME_HALO_ALPHA = 60

private const val BARREL_LENGTH_U = 92f
private const val BARREL_WIDTH_U = 46f
private const val BARREL_EDGE_WIDTH_U = 5f
private const val BARREL_GLOW_U = 22f
private const val BARREL_GLOW_ALPHA = 145
private const val BARREL_CORE_WIDTH_U = 6f
private const val BARREL_CORE_ALPHA = 210
private const val SWIRL_RADIUS_U = 26f
private const val SWIRL_WEDGES = 6
private const val COLLAR_RADIUS_U = 30f
private const val COLLAR_WIDTH_U = 5f

private const val PEG_GLOW_FACTOR = 5f
private const val PEG_GLOW_ALPHA = 130
private const val PEG_CORE_FACTOR = 0.45f

private const val GEM_GLOW_FACTOR = 2.2f
private const val GEM_GLOW_ALPHA = 150

private const val CUP_BOTTOM_TAPER = 0.72f
private const val CUP_PLEATS = 7
private const val CUP_PLEAT_WIDTH_U = 3f
private const val CUP_PLEAT_ALPHA = 140
private const val CUP_RIM_WIDTH_U = 9f
private const val CUP_RIM_GLOW_U = 38f
private const val CUP_RIM_GLOW_ALPHA = 220
private const val CUP_RIM_HIGHLIGHT_WIDTH_U = 3f
private const val CUP_RIM_HIGHLIGHT_ALPHA = 225

private const val AIM_DOT_RADIUS_U = 4f
private const val AIM_IMPACT_RADIUS_U = 13f
private const val AIM_IMPACT_WIDTH_U = 3f
private const val AIM_IMPACT_ALPHA = 200

private const val TRAIL_DOTS = 4
private const val TRAIL_SPACING_U = 11f
private const val TRAIL_RADIUS_U = 7f
private const val TRAIL_ALPHA = 170
private const val TRAIL_MIN_SPEED_U = 40f
