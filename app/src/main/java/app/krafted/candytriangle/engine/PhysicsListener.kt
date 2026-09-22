package app.krafted.candytriangle.engine

/**
 * Synchronous callbacks fired from inside [PhysicsWorld.step], in the step's deterministic order.
 *
 * This is the engine's only outward channel. B3 hangs gem smashes off [onPegContact] and candy
 * sensor checks off [afterStep]; B2's Candy Cup watches balls cross its lane in [afterStep]; B4
 * turns all of it into `SharedFlow<GameEvent>` for the ViewModel; D5 pitch-scales peg ticks by
 * `impactSpeed`.
 *
 * Callbacks run on the game thread in the middle of a step: they must be quick, must not block and
 * must not call [PhysicsWorld.step] or [PhysicsWorld.advance] re-entrantly. They **may** call
 * [PhysicsWorld.spawnBall] (the ball joins at the end of the step), [PhysicsWorld.removeBall], and
 * set [Peg.active] to false.
 *
 * Every method has a no-op default, so a listener overrides only what it needs.
 */
interface PhysicsListener {

    /**
     * [ball] struck [peg] (a peg or a gem body; see [Peg.kind]) with normal approach speed
     * [impactSpeed] > 0, u/s, measured in the peg's own frame. Fires once per impact — every
     * contact where [CollisionMath.resolve] judged the ball to be approaching — and never for a pure
     * separation. For a moving peg that includes the peg catching up with a ball that was already
     * leaving it: the impact is real, even when the ball's velocity comes out unchanged (it was
     * already moving with the peg, so there is nothing to reflect or hand over). A ball resting on
     * a peg micro-bounces almost every step at a few u/s, so consumers that care (audio) should
     * threshold on [impactSpeed].
     */
    fun onPegContact(ball: Ball, peg: Peg, impactSpeed: Float) {}

    /** [ball] bounced off [wall] with normal approach speed [impactSpeed] > 0, u/s. */
    fun onWallContact(ball: Ball, wall: WallSegment, impactSpeed: Float) {}

    /** The §3.3 stuck-ball rescue impulse was just applied to [ball]. */
    fun onStuckImpulse(ball: Ball) {}

    /**
     * [ball] left the board through the open base (its centre passed [PhysicsParams.exitY]) and has
     * been deactivated. Not fired for [PhysicsWorld.removeBall].
     */
    fun onBallExited(ball: Ball) {}

    /**
     * End of every step, after every ball has moved. Sensor-style checks belong here: at most
     * `maxStepDisplacement` of travel has happened since the previous call, so nothing can be
     * skipped over. [PhysicsWorld.balls] may still include balls deactivated during this step.
     *
     * The step counter has not ticked yet: [PhysicsWorld.stepCount] and [PhysicsWorld.timeSeconds]
     * still read the step's *start*, while balls and moving pegs are already at its end, time
     * `(stepCount + 1) / stepsPerSecond`. Timers keyed to simulation time should add that one step.
     */
    fun afterStep(world: PhysicsWorld) {}

    companion object {

        /** A listener that ignores everything. */
        val NONE: PhysicsListener = object : PhysicsListener {}
    }
}
