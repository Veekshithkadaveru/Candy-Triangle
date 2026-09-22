package app.krafted.candytriangle.engine

import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.GameConfig
import java.util.Collections

/**
 * The 240 Hz deterministic physics core (§3, §8 `PhysicsWorld.kt`): balls against the two slanted
 * walls, pegs and gem bodies, under gravity, restitution, the terminal-speed clamp and the
 * stuck-ball rescue.
 *
 * ## Determinism contract (§1 pillar 1, §11)
 *
 * Same [params], walls and pegs, plus the same calls at the same [stepCount]s, give bit-identical
 * ball state after every step, run after run. That holds because:
 * - simulation time is derived from the integer [stepCount], never accumulated in a float and never
 *   read from a clock;
 * - iteration order is fixed — balls in spawn order, walls left then right, pegs in the order given
 *   to the constructor — and no hash-ordered collection is ever iterated;
 * - there is no randomness anywhere (§3.2 `randomJitter` is 0 and stays unused);
 * - all trigonometry is bit-identical on the JVM and on ART: launch trig, once per shot
 *   ([PhysicsParams.launchVx] / [PhysicsParams.launchVy]), goes through `StrictMath`; per-step
 *   trig (moving pegs, [Peg.updateMotion]) goes through [DeterministicTrig], which is also
 *   allocation-free — JDK 21+ `StrictMath` allocates on every call; `sqrt` is IEEE-754 correctly
 *   rounded everywhere.
 *
 * ## Step pipeline — this order is part of the contract
 *
 * With `t = (stepCount + 1) / stepsPerSecond`, the simulation time at the *end* of this step:
 *
 * 1. Every peg with a [PegMotion] gets [Peg.updateMotion]`(t)`.
 * 2. For each ball in [balls] order that is still active:
 *    1. `prevX`/`prevY` <- `x`/`y`.
 *    2. `vy += gravity * dt` (semi-implicit Euler: velocity, then position).
 *    3. If speed exceeds `maxBallSpeed`, scale the velocity down to it. This is the *only* speed
 *       clamp, and it sits immediately before the position update, so every step's displacement is
 *       at most [PhysicsParams.maxStepDisplacement] — the whole §3.3 anti-tunnelling argument.
 *    4. `x += vx * dt`, `y += vy * dt`.
 *    5. Contacts: up to [MAX_CONTACT_PASSES] passes. Each pass tests the walls in order, then every
 *       active peg in order, resolving each overlap on the spot with [CollisionMath.resolve] (walls:
 *       their own restitution, no surface velocity; pegs: [Peg.restitution], [Peg.vx] and
 *       `movingPegTransfer`, damping `tangentialDamping` for both). A bounce fires
 *       [PhysicsListener.onWallContact] / [PhysicsListener.onPegContact]. Stop after the first pass
 *       that finds no overlap.
 *    6. Stuck rescue (§3.3): if speed < `stuckSpeedThreshold`, `stuckSteps++`, else `stuckSteps = 0`.
 *       Once `stuckSteps > stuckDwellSteps`: add `stuckImpulseSpeed` along `normalize(sign, 1)`
 *       (`(sign, 0)` when `!stuckDownward`), where `sign` is +1 left of `stuckAxisX`, -1 right of it,
 *       and **+1 exactly on the axis** — a deterministic tie-break, without which a ball balanced on
 *       an on-axis peg would be pushed straight back down into it forever. Then reset `stuckSteps`
 *       to 0 and fire [PhysicsListener.onStuckImpulse].
 *    7. Exit: if `y > exitY`, or any of x, y, vx, vy is non-finite (a defensive exit that valid
 *       params cannot trigger — zero crash tolerance, §13), set `active = false` and fire
 *       [PhysicsListener.onBallExited].
 * 3. [PhysicsListener.afterStep].
 * 4. Inactive balls are dropped from [balls], order preserved; balls spawned during the step are
 *    appended, in spawn order. They first move on the next step.
 * 5. [stepCount] increments.
 *
 * Balls do not collide with each other (a Split Gem clone passes through its parent).
 *
 * ## Threading and allocation
 *
 * Confined to the game thread; not thread-safe. [step] must not allocate — no boxing, no iterators
 * (index loops only), no lambdas, no temporary objects — since it runs ~240 times a second and GC
 * pauses on the game thread are dropped frames. One reusable [Contact] serves every test.
 *
 * ## Implementation notes
 *
 * - **Listener mutations.** A ball whose callback removes it ([removeBall] from inside
 *   [PhysicsListener.onPegContact], say) stops its pipeline at that point: no further contacts, no
 *   stuck rescue, and never [PhysicsListener.onBallExited]. A ball removed before its turn in the
 *   step is skipped outright. Balls spawned mid-step wait in a pending list; one also removed in the
 *   same step never joins [balls]. [step] or [advance] called from a callback throws
 *   [IllegalStateException] — a programmer error, not a config one.
 * - **Moving pegs** are found once, at construction, so step 1 never visits a static peg; they are
 *   also placed at `t = 0` then, so a renderer or query before the first step sees the same state
 *   the step would produce.
 * - **Reuse by B2's aim guide.** Steps 2.1–2.4 ([integrate]) and 2.5 ([resolveContacts]) are
 *   separate internal pieces, with the contact events routed to a caller-chosen listener, so a
 *   "ghost ball" prediction can run exactly the step's integration and contact response without
 *   touching [balls] or this world's [listener].
 */
class PhysicsWorld(
    val params: PhysicsParams,
    walls: List<WallSegment>,
    /**
     * The board's pegs and gem bodies, in the order the step tests them. The list is copied, but
     * the world takes **ownership of the [Peg] instances** in it: it moves every peg with a
     * [PegMotion] (to `t = 0` at construction, then on every step), and B3 deactivates smashed gems
     * through [Peg.active]. A peg must therefore never be shared between two worlds — their clocks
     * would fight over its position, and a gem smashed in one would vanish from the other. B2's
     * `LayoutBuilder` must build fresh pegs for every world.
     */
    pegs: List<Peg>,
    /** Receives every event. Swappable, e.g. by B4 when a level session starts. */
    var listener: PhysicsListener = PhysicsListener.NONE,
) {

    /** The walls, in the fixed order the step tests them (left, then right, from [WallSegment.boardWalls]). */
    val walls: List<WallSegment> = walls.toList()

    /** Pegs and gem bodies, in the fixed order the step tests them. The set never changes. */
    val pegs: List<Peg> = pegs.toList()

    // -- step state -------------------------------------------------------------------------------

    /** [walls] and [pegs] as arrays: the hot loops index them directly, with no interface calls. */
    private val wallArray: Array<WallSegment> = this.walls.toTypedArray()
    private val pegArray: Array<Peg> = this.pegs.toTypedArray()

    /** Indices into [pegArray] of the pegs with a [PegMotion], in peg order. */
    private val movingPegIndices: IntArray =
        pegArray.indices.filter { pegArray[it].motion != null }.toIntArray()

    /** The live balls, in spawn order; [balls] is a read-only view of it. */
    private val ballList = ArrayList<Ball>(INITIAL_BALL_CAPACITY)
    private val ballsView: List<Ball> = Collections.unmodifiableList(ballList)

    /** Balls spawned from a listener during a step, waiting for the end of it (pipeline step 4). */
    private val pendingBalls = ArrayList<Ball>(INITIAL_BALL_CAPACITY)

    /** The one scratch [Contact] every overlap test writes into. */
    private val contact = Contact()

    /** Frame-time accumulator behind [advance] and [interpolationAlpha]. */
    private val timestep = FixedTimestep(params.stepsPerSecond)

    /** `gravity * dt`, the per-step velocity gain: the same float product the step would compute. */
    private val gravityDt: Float = params.gravity * params.dt

    private var completedSteps = 0L
    private var nextBallId = 0

    /** True for the duration of [step]; decides whether spawns and removals apply now or at its end. */
    private var inStep = false

    init {
        placeMovingPegs(0.0)
    }

    /**
     * Balls in spawn order. Between steps, only active balls; see the step pipeline for during.
     *
     * A read-only live view, not a snapshot: it changes as balls spawn and leave, so a caller
     * iterating it between steps must not call [removeBall] in the same loop.
     */
    val balls: List<Ball> get() = ballsView

    /** Steps completed since construction. */
    val stepCount: Long get() = completedSteps

    /** Simulated time, s: `stepCount / stepsPerSecond`, computed from the counter, never summed. */
    val timeSeconds: Double get() = completedSteps.toDouble() / params.stepsPerSecond

    /** True while any ball is active or waiting to join — i.e. the current drop has not ended. */
    val hasActiveBalls: Boolean
        get() {
            for (i in 0 until ballList.size) if (ballList[i].active) return true
            for (i in 0 until pendingBalls.size) if (pendingBalls[i].active) return true
            return false
        }

    /** Render interpolation factor from the internal [FixedTimestep], `[0, 1)`; see [Ball.prevX]. */
    val interpolationAlpha: Float get() = timestep.alpha

    /**
     * Fires a ball from the launcher: spawned at ([PhysicsParams.spawnX], [PhysicsParams.spawnY])
     * with velocity ([PhysicsParams.launchVx], [PhysicsParams.launchVy]) for [aimRadians] (radians
     * from straight down, positive toward +x; clamped to plus or minus 70 degrees).
     *
     * Does not enforce "one ball in flight" (§2) — that is a game rule, owned by B4's session.
     */
    fun launch(aimRadians: Float, skin: BallSkin = BallSkin.DEFAULT): Ball =
        spawnBall(
            x = params.spawnX,
            y = params.spawnY,
            vx = params.launchVx(aimRadians),
            vy = params.launchVy(aimRadians),
            skin = skin,
        )

    /**
     * Adds a ball with an explicit state (B3's Split Gem clone; tests). Its radius is
     * [PhysicsParams.ballRadius] and its id the next in spawn order. Called between steps, the ball
     * joins [balls] immediately; called from a listener during a step, it joins at the end of that
     * step, per the pipeline.
     *
     * Any state is accepted: a non-finite one is retired by the step's defensive exit (pipeline step
     * 2.7) rather than rejected here.
     */
    fun spawnBall(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        skin: BallSkin = BallSkin.DEFAULT,
    ): Ball {
        val ball = Ball(
            id = nextBallId++,
            x = x,
            y = y,
            vx = vx,
            vy = vy,
            radius = params.ballRadius,
            skin = skin,
        )
        if (inStep) pendingBalls.add(ball) else ballList.add(ball)
        return ball
    }

    /**
     * Deactivates [ball] without firing [PhysicsListener.onBallExited] — for B2's Candy Cup catch.
     * Between steps it leaves [balls] immediately; during a step, at the end of it. Idempotent.
     *
     * A ball this world does not hold (never spawned here, or already gone) is left untouched: the
     * call is then a no-op rather than a way to deactivate another world's ball.
     */
    fun removeBall(ball: Ball) {
        if (inStep) {
            // Deactivated now, so the rest of this step skips it; pipeline step 4 drops it.
            if (indexOfBall(ballList, ball) >= 0 || indexOfBall(pendingBalls, ball) >= 0) {
                ball.active = false
            }
            return
        }
        val index = indexOfBall(ballList, ball)
        if (index < 0) return
        ball.active = false
        ballList.removeAt(index)
    }

    /** Runs exactly one fixed step of 1/[PhysicsParams.stepsPerSecond] s. See the class docs. */
    fun step() {
        check(!inStep) { "PhysicsWorld.step() called re-entrantly from a PhysicsListener callback" }
        inStep = true
        try {
            // 1. Moving pegs, at the time this step ends.
            placeMovingPegs((completedSteps + 1).toDouble() / params.stepsPerSecond)

            // 2. Balls, in spawn order. Nothing changes ballList's size mid-step: spawns go to
            //    pendingBalls and removals only deactivate.
            for (i in 0 until ballList.size) {
                val ball = ballList[i]
                if (ball.active) stepBall(ball)
            }

            // 3.
            listener.afterStep(this)

            // 4.
            dropInactiveAndAdmitPending()

            // 5.
            completedSteps++
        } finally {
            inStep = false
        }
    }

    /**
     * Banks [frameNanos] of wall-clock time in the internal [FixedTimestep] and runs however many
     * whole steps it releases. Returns that number. This is D1's per-frame entry point.
     */
    fun advance(frameNanos: Long): Int {
        check(!inStep) { "PhysicsWorld.advance() called re-entrantly from a PhysicsListener callback" }
        val released = timestep.advance(frameNanos)
        for (i in 0 until released) step()
        return released
    }

    /**
     * Discards frame time banked by [advance] ([FixedTimestep.reset]); the simulation itself is
     * untouched. D1 calls it on resume from pause, so the first frame back does not replay the
     * pause as a burst of catch-up steps.
     */
    fun resetTimestep() {
        timestep.reset()
    }

    // -- pipeline pieces ----------------------------------------------------------------------------

    /** Pipeline step 2, for one active ball. Stops early if a callback removes the ball. */
    private fun stepBall(ball: Ball) {
        integrate(ball)
        resolveContacts(ball, listener)
        if (!ball.active) return
        rescueIfStuck(ball)
        if (!ball.active) return
        exitIfOut(ball)
    }

    /**
     * Pipeline steps 2.1–2.4 for [ball]: remember the start position, apply gravity, clamp to the
     * terminal speed, move. Semi-implicit Euler — velocity first, then position with the new
     * velocity — so the displacement bound holds for the step actually taken.
     *
     * Internal so B2's aim guide can advance a ghost ball with exactly the step's integration.
     */
    internal fun integrate(ball: Ball) {
        ball.prevX = ball.x
        ball.prevY = ball.y
        ball.vy += gravityDt
        val speed = ball.speed
        if (speed > params.maxBallSpeed) {
            val scale = params.maxBallSpeed / speed
            ball.vx *= scale
            ball.vy *= scale
        }
        ball.x += ball.vx * params.dt
        ball.y += ball.vy * params.dt
    }

    /**
     * Pipeline step 2.5 for [ball]: up to [MAX_CONTACT_PASSES] passes over the walls, then the
     * active pegs, each in order, resolving every overlap on the spot. A bounce (a strictly positive
     * impact speed from [CollisionMath.resolve]) is reported to [events]; a pure separation is not.
     *
     * The step passes [listener]. Internal, with the listener as a parameter, so B2's aim guide can
     * run a ghost ball through the identical response and learn its first contact from its own
     * listener. Returns early if a callback deactivates [ball].
     */
    internal fun resolveContacts(ball: Ball, events: PhysicsListener) {
        val damping = params.tangentialDamping
        var pass = 0
        while (pass < MAX_CONTACT_PASSES) {
            pass++
            var overlapped = false

            for (w in wallArray.indices) {
                val wall = wallArray[w]
                if (!wall.contact(ball.x, ball.y, ball.radius, contact)) continue
                overlapped = true
                val impact = CollisionMath.resolve(
                    ball = ball,
                    contact = contact,
                    restitution = wall.restitution,
                    tangentialDamping = damping,
                    surfaceVx = 0f,
                    transfer = 0f,
                )
                if (impact > 0f) {
                    events.onWallContact(ball, wall, impact)
                    if (!ball.active) return
                }
            }

            for (p in pegArray.indices) {
                val peg = pegArray[p]
                if (!peg.active) continue
                val hit = CollisionMath.circleContact(
                    ballX = ball.x,
                    ballY = ball.y,
                    ballRadius = ball.radius,
                    cx = peg.x,
                    cy = peg.y,
                    colliderRadius = peg.radius,
                    out = contact,
                )
                if (!hit) continue
                overlapped = true
                val impact = CollisionMath.resolve(
                    ball = ball,
                    contact = contact,
                    restitution = peg.restitution,
                    tangentialDamping = damping,
                    surfaceVx = peg.vx,
                    transfer = params.movingPegTransfer,
                )
                if (impact > 0f) {
                    events.onPegContact(ball, peg, impact)
                    if (!ball.active) return
                }
            }

            if (!overlapped) return
        }
    }

    /**
     * Pipeline step 2.6, the §3.3 stuck rescue. The sign test is written `x > axis` so that both
     * exactly-on-axis and (defensively) NaN positions take the +1 branch.
     */
    private fun rescueIfStuck(ball: Ball) {
        if (ball.speed < params.stuckSpeedThreshold) ball.stuckSteps++ else ball.stuckSteps = 0
        if (ball.stuckSteps <= params.stuckDwellSteps) return

        val sign = if (ball.x > params.stuckAxisX) -1f else 1f
        val impulse = params.stuckImpulseSpeed
        if (params.stuckDownward) {
            val diagonal = impulse * INV_SQRT_2
            ball.vx += sign * diagonal
            ball.vy += diagonal
        } else {
            ball.vx += sign * impulse
        }
        ball.stuckSteps = 0
        listener.onStuckImpulse(ball)
    }

    /** Pipeline step 2.7: out through the open base, or defensively retired as non-finite. */
    private fun exitIfOut(ball: Ball) {
        val out = ball.y > params.exitY ||
            !ball.x.isFinite() || !ball.y.isFinite() ||
            !ball.vx.isFinite() || !ball.vy.isFinite()
        if (!out) return
        ball.active = false
        listener.onBallExited(ball)
    }

    /**
     * Pipeline step 4, allocation-free: an in-place write-index compaction of [ballList] (order
     * preserved), trimmed from the tail, then the still-active pending balls in spawn order.
     */
    private fun dropInactiveAndAdmitPending() {
        var write = 0
        for (read in 0 until ballList.size) {
            val ball = ballList[read]
            if (!ball.active) continue
            if (write != read) ballList[write] = ball
            write++
        }
        while (ballList.size > write) ballList.removeAt(ballList.size - 1)

        for (i in 0 until pendingBalls.size) {
            val ball = pendingBalls[i]
            if (ball.active) ballList.add(ball)
        }
        pendingBalls.clear()
    }

    /** Pipeline step 1 for simulation time [timeSeconds]; also run once at construction for t = 0. */
    private fun placeMovingPegs(timeSeconds: Double) {
        for (k in movingPegIndices.indices) pegArray[movingPegIndices[k]].updateMotion(timeSeconds)
    }

    /** Identity search: [Ball] has no `equals`, and this says so rather than relying on it. */
    private fun indexOfBall(list: ArrayList<Ball>, ball: Ball): Int {
        for (i in 0 until list.size) if (list[i] === ball) return i
        return -1
    }

    companion object {

        /** Maximum contact-resolution passes per ball per step. */
        const val MAX_CONTACT_PASSES: Int = 3

        /**
         * `1 / sqrt(2)`, the nearest float: each component of the stuck impulse's unit diagonal
         * `normalize(sign, 1)`. A constant rather than a runtime `sqrt`, so the rescue direction is
         * a literal anyone can check.
         */
        private const val INV_SQRT_2: Float = 0.70710677f

        /** One launched ball plus a few Split Gem clones fit without the lists ever growing. */
        private const val INITIAL_BALL_CAPACITY = 4

        /** A world for [config]'s board: resolved [PhysicsParams] and the two §3.1 walls. */
        fun create(
            config: GameConfig,
            pegs: List<Peg>,
            listener: PhysicsListener = PhysicsListener.NONE,
        ): PhysicsWorld {
            val params = PhysicsParams.from(config)
            return PhysicsWorld(
                params = params,
                walls = WallSegment.boardWalls(config.board, params.restitutionWall),
                pegs = pegs,
                listener = listener,
            )
        }
    }
}
