package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsListener
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.engine.WallSegment
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LevelSession
import kotlin.math.sqrt

/**
 * Production pipeline connecting engine collisions to game rules (§4, §5).
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
    private var remainingSugarStorms = 1

    override fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {
        if (peg.kind == ColliderKind.GEM) {
            // Find the logical Gem object
            val gem = boardState.gems.firstOrNull { it.peg.id == peg.id }
            if (gem != null && gem.active) {
                boardState.registerGemBreak(gem)
                peg.active = false // Tell engine to ignore this collider from now on
                
                session.onGemSmashed(gem.type, boardState)
                chainTracker.onGemHit()

                val effectResult = GemEffectHandlers.applyEffect(
                    effect = config.gem(gem.type)?.effect ?: app.krafted.candytriangle.level.GemEffect.None,
                    gem = gem,
                    state = boardState,
                    latticeSpacing = latticeSpacing,
                    remainingSugarStorms = remainingSugarStorms
                )

                // Process effect consequences
                if (effectResult.sugarStormConsumed) {
                    remainingSugarStorms--
                }
                
                if (effectResult.magnetDuration > 0f) {
                    magnetDurationSeconds = effectResult.magnetDuration
                    magnetRadius = effectResult.magnetRadius
                }
                
                if (effectResult.refundBalls > 0) {
                    for (i in 0 until effectResult.refundBalls) {
                        session.addBall()
                    }
                }
                
                // Spawn clones
                if (effectResult.spawnBalls > 0) {
                    for (i in 0 until effectResult.spawnBalls) {
                        val clone = world.spawnBall(
                            x = gem.x,
                            y = gem.y,
                            vx = ball.vx * (if (effectResult.mirrorHorizontal && i % 2 == 0) -1f else 1f),
                            vy = ball.vy,
                            skin = ball.skin
                        )
                        session.onBallSpawned(clone)
                    }
                }
                
                // Popped candies
                for (candy in effectResult.poppedCandies) {
                    if (candy.active) {
                        candy.active = false
                        val chainResult = chainTracker.onCandyPopped(candy, direct = false)
                        session.onCandyPopped(candy.color, isDirect = false, chainPosition = chainResult.chainLength, boardState = boardState)
                    }
                }
            }
        }
    }

    override fun afterStep(world: PhysicsWorld) {
        // Update cup position
        cup.updateMotion(params.dt)
        
        // Handle Magnet Effect
        if (magnetDurationSeconds > 0f) {
            magnetDurationSeconds -= params.dt
            val pullSpeed = 400f * params.dt // arbitrary pull speed for magnet
            
            for (ball in world.balls) {
                if (!ball.active) continue
                val pulledCandies = boardState.getCandiesInRadius(ball.x, ball.y, magnetRadius)
                for (candy in pulledCandies) {
                    if (!candy.active) continue
                    val dx = ball.x - candy.x
                    val dy = ball.y - candy.y
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist > 0.01f) {
                        val move = pullSpeed.coerceAtMost(dist)
                        candy.x += (dx / dist) * move
                        candy.y += (dy / dist) * move
                    }
                }
            }
        }
        
        // Sensor checks for candies
        for (ball in world.balls) {
            if (!ball.active) continue
            
            // Check cup catch if ball crosses the cup lane
            // The cup lane is at the base. PRD says exit base -> caught or lost.
            // Actually, we can check it right before exit.
            // The physics engine checks `y > params.exitY` and fires `onBallExited`.
            // But we can check cup catch in `afterStep` if ball is in the cup region.
            if (ball.y >= 1280f && ball.y <= 1330f) {
                if (cup.checkCatch(ball)) {
                    world.removeBall(ball)
                    session.onCupCaught(boardState)
                    // The session manages activeBallsInFlight decrement on onBallExited.
                    // But if we removeBall(), onBallExited won't fire. So we need to notify session.
                    session.onBallExited(boardState, chainTracker)
                    continue
                }
            }
            
            for (candy in boardState.candies) {
                if (candy.active && candy.contact(ball.x, ball.y, ball.radius, config.physics.candyRadius)) {
                    candy.active = false
                    val chainResult = chainTracker.onCandyPopped(candy, direct = true)
                    session.onCandyPopped(candy.color, isDirect = true, chainPosition = chainResult.chainLength, boardState = boardState)
                    
                    if (chainResult.chainLength > 1) {
                        session.onChainAdvanced(candy.color, chainResult.chainLength, boardState)
                    }
                    
                    if (chainResult.triggerSugarPop) {
                        // Sugar pop pops candies within 2 * d
                        val radius = 2f * latticeSpacing
                        val popped = boardState.getCandiesInRadius(candy.x, candy.y, radius).filter { it.color == candy.color }
                        for (p in popped) {
                            if (p.active) {
                                p.active = false
                                session.onCandyPopped(p.color, isDirect = false, chainPosition = 0, boardState = boardState)
                            }
                        }
                    }
                    
                    if (chainResult.triggerExtraBall) {
                        session.addBall()
                    }
                }
            }
        }
    }
    
    override fun onBallExited(ball: Ball) {
        session.onBallExited(boardState, chainTracker)
    }
}
