package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsListener
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.LevelSession
import kotlin.math.sqrt

/**
 * Production pipeline connecting engine collisions to game rules (§4, §5).
 *
 * Runs synchronously inside [PhysicsWorld.step] on the game thread (B1 note 13):
 * - [onPegContact] — gem smashes: break, score, §4.2 effect, `GameEvent.GemEffectTriggered`;
 * - [afterStep] — cup motion and catch (§2), Magnet drift (§4.2), candy sensors, chains and Sugar
 *   Pop (§4.1);
 * - [onBallExited] — drop bookkeeping in [LevelSession].
 *
 * The per-step paths ([afterStep]) iterate with index loops and query no allocating helpers, in
 * keeping with the engine's zero-allocation step. Gem smashes and pops are rare, one-off events and
 * may allocate.
 */
class GamePhysicsListener(
    private val session: LevelSession,
    private val boardState: BoardState,
    private val chainTracker: ChainTracker,
    private val cup: CandyCup,
    private val config: GameConfig,
    private val params: PhysicsParams,
    private val latticeSpacing: Float
) : PhysicsListener {

    lateinit var world: PhysicsWorld

    private var magnetDurationSeconds = 0f
    private var magnetRadius = 0f

    /**
     * Sugar Storm charges left this level: `maxPerLevel` of the config's
     * [GemEffect.PopAllOfLargestColour] (§4.2 "Max 1/level"), or 1 if the config defines none.
     */
    var remainingSugarStorms: Int = sugarStormCap(config)
        private set

    private val sugarPopRadius = config.chains.sugarPopRadiusFactor * latticeSpacing
    private val candyRadius = config.physics.candyRadius
    private val cupLaneTop = config.cup.laneYTop
    private val cupLaneBottom = config.cup.laneYBottom

    override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
        if (peg.kind != ColliderKind.GEM) return
        val gem = findGem(peg) ?: return
        if (!gem.active) return

        boardState.registerGemBreak(gem)
        peg.active = false // The engine ignores this collider from now on.

        session.onGemSmashed(gem.type, boardState)
        chainTracker.onGemHit()

        val effect = config.gem(gem.type)?.effect ?: GemEffect.None
        val effectResult = GemEffectHandlers.applyEffect(
            effect = effect,
            gem = gem,
            state = boardState,
            latticeSpacing = latticeSpacing,
            remainingSugarStorms = remainingSugarStorms,
            rowSpacingFactor = config.board.lattice.rowSpacingFactor,
        )

        if (effectResult.sugarStormConsumed) remainingSugarStorms--

        if (effectResult.magnetDuration > 0f) {
            magnetDurationSeconds = effectResult.magnetDuration
            magnetRadius = effectResult.magnetRadius
        }

        repeat(effectResult.refundBalls) { session.addBall() }

        // Split Gem clones.
        for (i in 0 until effectResult.spawnBalls) {
            val clone = world.spawnBall(
                x = gem.x,
                y = gem.y,
                vx = ball.vx * (if (effectResult.mirrorHorizontal && i % 2 == 0) -1f else 1f),
                vy = ball.vy,
                skin = ball.skin,
            )
            session.onBallSpawned(clone)
        }

        // Effect pops: score and objectives, never the chain (§4.1 note).
        var popped = 0
        for (candy in effectResult.poppedCandies) {
            if (!candy.active) continue
            candy.active = false
            popped++
            val chainResult = chainTracker.onCandyPopped(candy, direct = false)
            session.onCandyPopped(candy.color, isDirect = false, chainPosition = chainResult.chainLength, boardState = boardState)
        }

        // A Sugar Storm past its per-level cap fires no effect, so it gets no VFX event either.
        val effectFired = when (effect) {
            GemEffect.None -> false
            is GemEffect.PopAllOfLargestColour -> effectResult.sugarStormConsumed
            else -> true
        }
        if (effectFired) session.onGemEffectTriggered(gem.type, gem.x, gem.y, popped)
    }

    override fun afterStep(world: PhysicsWorld) {
        cup.updateMotion(params.dt)

        if (magnetDurationSeconds > 0f) {
            magnetDurationSeconds -= params.dt
            applyMagnet(world)
        }

        // Sensor checks. Index loop, re-reading size: between steps (tests call afterStep directly)
        // removeBall shrinks the list at once, so the next ball slides into the current index.
        val balls = world.balls
        var i = 0
        while (i < balls.size) {
            val ball = balls[i]
            if (!ball.active) {
                i++
                continue
            }

            // Cup catch: the ball is in the cup lane and within the cup's width (§2).
            if (ball.y >= cupLaneTop && ball.y <= cupLaneBottom && cup.checkCatch(ball)) {
                val sizeBefore = balls.size
                world.removeBall(ball) // Fires no onBallExited, so the session is told here.
                session.onCupCaught(boardState)
                session.onBallExited(boardState, chainTracker)
                if (balls.size == sizeBefore) i++
                continue
            }

            collectCandies(ball)
            i++
        }
    }

    override fun onBallExited(ball: Ball) {
        session.onBallExited(boardState, chainTracker)
    }

    /**
     * Candy sensors for one ball, with chains, Sugar Pop and the chain extra ball. A touched candy
     * the Magnet pulled in scores and chains as direct only per
     * `chains.magnetPullCountsAsDirectContact` ([ChainTracker.countsAsDirect]).
     */
    private fun collectCandies(ball: Ball) {
        val candies = boardState.candies
        for (k in candies.indices) {
            val candy = candies[k]
            if (!candy.active || !candy.contact(ball.x, ball.y, ball.radius, candyRadius)) continue

            candy.active = false
            val direct = chainTracker.countsAsDirect(touched = true, magnetPulled = candy.magnetPulled)
            val chainResult = chainTracker.onCandyPopped(candy, direct)
            session.onCandyPopped(candy.color, isDirect = direct, chainPosition = chainResult.chainLength, boardState = boardState)

            if (chainResult.extended && chainResult.chainLength > 1) {
                session.onChainAdvanced(candy.color, chainResult.chainLength, boardState)
            }

            if (chainResult.triggerSugarPop) sugarPop(candy)

            if (chainResult.triggerExtraBall) session.addBall()
        }
    }

    /**
     * §4.1 Sugar Pop: pops every active candy of [trigger]'s colour within
     * `chains.sugarPopRadiusFactor · d` of it. Effect pops score flat and do not extend the chain.
     */
    private fun sugarPop(trigger: Candy) {
        val radiusSq = sugarPopRadius * sugarPopRadius
        val candies = boardState.candies
        var popped = 0
        for (k in candies.indices) {
            val c = candies[k]
            if (!c.active || c.color != trigger.color) continue
            val dx = c.x - trigger.x
            val dy = c.y - trigger.y
            if (dx * dx + dy * dy > radiusSq) continue
            c.active = false
            popped++
            session.onCandyPopped(c.color, isDirect = false, chainPosition = 0, boardState = boardState)
        }
        session.onSugarPop(trigger.color, trigger.x, trigger.y, popped)
    }

    /**
     * §4.2 Magnet: every active candy within [magnetRadius] of an active ball drifts toward it at
     * [MAGNET_PULL_SPEED], never overshooting the ball's centre. The sensor pass that follows turns
     * the resulting contact into a hit; each moved candy is flagged [Candy.magnetPulled] so that
     * `chains.magnetPullCountsAsDirectContact` decides whether the hit is direct.
     */
    private fun applyMagnet(world: PhysicsWorld) {
        val pullStep = MAGNET_PULL_SPEED * params.dt
        val radiusSq = magnetRadius * magnetRadius
        val balls = world.balls
        val candies = boardState.candies
        for (b in balls.indices) {
            val ball = balls[b]
            if (!ball.active) continue
            for (k in candies.indices) {
                val candy = candies[k]
                if (!candy.active) continue
                val dx = ball.x - candy.x
                val dy = ball.y - candy.y
                val distSq = dx * dx + dy * dy
                if (distSq > radiusSq) continue
                val dist = sqrt(distSq)
                if (dist > MAGNET_MIN_PULL_DISTANCE) {
                    val move = pullStep.coerceAtMost(dist)
                    candy.x += (dx / dist) * move
                    candy.y += (dy / dist) * move
                    candy.magnetPulled = true
                }
            }
        }
    }

    private fun findGem(peg: Peg): Gem? {
        val gems = boardState.gems
        for (k in gems.indices) if (gems[k].peg.id == peg.id) return gems[k]
        return null
    }

    companion object {

        /**
         * Magnet Gem drift speed of a pulled candy, u/s (§4.2). `config.json` has no field for it,
         * so it lives here: 400 u/s crosses a full `3·d` radius (≤ 270 u) in about 0.7 s, well
         * inside the 2.0 s effect, and stays far below a falling ball's speed so the pull reads as
         * a drift rather than a snap.
         */
        const val MAGNET_PULL_SPEED: Float = 400f

        /** Below this distance, u, a candy is left where it is: it already overlaps the ball. */
        private const val MAGNET_MIN_PULL_DISTANCE: Float = 0.01f

        private fun sugarStormCap(config: GameConfig): Int {
            for (def in config.gems) {
                val effect = def.effect
                if (effect is GemEffect.PopAllOfLargestColour) return effect.maxPerLevel.coerceAtLeast(0)
            }
            return 1
        }
    }
}
