package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.Ball
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelSession

/**
 * One playable level, fully wired: layout, candies, rules and physics (§8).
 *
 * The single entry point from a [LevelDef] to a running board. D1's `GameThread` drives [world];
 * the C2 suite uses [launch] and [stepUntilDropEnds]. Every call builds fresh `Peg`s, so two boards
 * for the same level never share mutable state (B1 note 14).
 */
class LevelBoard private constructor(
    val level: LevelDef,
    val config: GameConfig,
    val params: PhysicsParams,
    val layout: BoardLayout,
    val boardState: BoardState,
    val chainTracker: ChainTracker,
    val cup: CandyCup,
    val session: LevelSession,
    val listener: GamePhysicsListener,
    val world: PhysicsWorld,
    val launcher: Launcher,
) {

    val candies: List<Candy> get() = boardState.candies

    /** True while any ball of the current drop is still in flight. */
    val isDropActive: Boolean get() = session.activeBallsInFlight > 0

    /** Aims the launcher and fires one ball, or returns null when the session refuses. */
    fun launch(aimRadians: Float, skin: BallSkin = BallSkin.DEFAULT): Ball? {
        launcher.aimRadians = aimRadians
        return session.launchBall(world, launcher.aimRadians, skin, boardState, chainTracker)
    }

    /** Steps until the current drop ends or [maxSteps] elapse. Returns the steps taken. */
    fun stepUntilDropEnds(maxSteps: Int = DEFAULT_MAX_DROP_STEPS): Int {
        var n = 0
        while (isDropActive && n < maxSteps) {
            world.step()
            n++
        }
        return n
    }

    companion object {

        /** 60 s of simulated time — far past any real drop (B1 measured ≤ 9.2 s). */
        const val DEFAULT_MAX_DROP_STEPS: Int = 240 * 60

        fun create(level: LevelDef, config: GameConfig): LevelBoard {
            val params = PhysicsParams.from(config)
            val layout = LayoutBuilder.build(level, config, params)
            val candies = CandyPlacer.place(level, layout.gems, config)
            val boardState = BoardState(candies, layout.gems, params)
            val chainTracker = ChainTracker(config.chains)
            val cup = CandyCup(
                width = config.cup.cupWidth,
                speed = level.cup.speed.coerceIn(config.cup.speedMin, config.cup.speedMax),
                boardWidth = config.board.baseWidth,
            )
            val session = LevelSession(level, config.scoring, config.cup)
            val listener = GamePhysicsListener(
                session = session,
                boardState = boardState,
                chainTracker = chainTracker,
                cup = cup,
                config = config,
                params = params,
                latticeSpacing = level.layout.spacing,
            )
            val world = PhysicsWorld.create(config, layout.pegs, listener)
            listener.world = world
            return LevelBoard(
                level, config, params, layout, boardState, chainTracker, cup, session,
                listener, world, Launcher(params),
            )
        }
    }
}
