package app.krafted.candytriangle.ui.game

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.level.CrownCalculator
import app.krafted.candytriangle.level.ObjectiveDef
import app.krafted.candytriangle.level.ObjectiveType

/**
 * Turns a live [LevelBoard] into a [HudState], on the game thread, only when something changed.
 *
 * ## Why this class exists
 *
 * `LevelSession`'s fields are plain non-volatile vars written on the game thread, so the main
 * thread may never read them directly (torn and stale reads — exactly what the session's KDoc
 * warns about). The safe instant is inside `GameLoopListener.onFrame`, between `world.advance()`
 * and the draw, when no step is in progress and `totalScore`, `remainingBalls`,
 * `activeBallsInFlight`, `isLevelComplete`, `isLevelFailed` and `ObjectiveTracker` are mutually
 * consistent. That is a **push**, not a poll, and this class is the push's dirty check.
 *
 * It is also why the mapper is a plain class with no Android and no Compose types: §11's suite is
 * JVM-only, and every non-trivial HUD computation therefore has to live outside a composable.
 *
 * ## Allocation contract
 *
 * [update] runs 60 times a second. It compares the session against primitives cached at the last
 * publish — scalars plus an `IntArray` of objective progress — and returns `null` when nothing
 * moved, so an idle frame allocates nothing. Only a genuine change allocates one [HudState] and
 * its objective list.
 *
 * `CLEAR_COLOR` is the one objective with no counter: `ObjectiveTracker` evaluates it live against
 * `BoardState`, and `getCandiesByColor` allocates. Its dirty key is therefore the number of active
 * candies, counted with an index loop over the ~40-entry list; only when that count moves does the
 * mapper call `getCandiesByColor` and `isComplete`.
 *
 * `LevelSession.collectedByColor` builds a fresh `LinkedHashMap` per read, so it is read **exactly
 * once per attempt**, in [outcome], and never per frame.
 *
 * ## Threading
 *
 * Construct on the thread that built the board, before the loop starts; call [update], [isTerminal]
 * and [outcome] on the game thread only. Nothing here is synchronised, and nothing needs to be:
 * there is exactly one caller.
 *
 * Owner: Agent 2 (`ui/game`).
 */
class HudStateMapper(private val board: LevelBoard) {

    private val session = board.session
    private val tracker = session.objectiveTracker
    private val boardState = board.boardState
    private val level = board.level

    private val defs: List<ObjectiveDef> = level.objectives
    private val objectiveCount = defs.size

    /**
     * Display targets, fixed for the attempt.
     *
     * `count ?: 1` for COLLECT_CANDY / COLLECT_GEM / CHAIN / CUP and `score ?: 1` for SCORE mirror
     * `ObjectiveTracker.isComplete`'s own fallbacks, so the bar can never read "3 / 0" against a
     * level whose JSON dropped the field. `CLEAR_COLOR` has no authored target: it gets the candies
     * of that colour actually placed, read once here while the board is still untouched.
     */
    private val targets = IntArray(objectiveCount) { i -> targetFor(defs[i]) }

    private val hasClearColor = defs.any { it.type == ObjectiveType.CLEAR_COLOR }

    // ---- primitives cached at the last publish; the whole dirty check ----------------------

    private val progress = IntArray(objectiveCount)
    private val completed = BooleanArray(objectiveCount)
    private var published = false
    private var lastScore = 0
    private var lastDropSubtotal = 0
    private var lastBallsRemaining = 0
    private var lastBallsInFlight = 0
    private var lastComplete = false
    private var lastFailed = false
    private var lastRefusals = 0
    private var lastActiveCandies = -1

    /** True once `LevelSession` has decided the attempt, either way. Game thread only. */
    val isTerminal: Boolean
        get() = session.isLevelComplete || session.isLevelFailed

    /**
     * The HUD snapshot if anything moved since the last publish, else `null`.
     *
     * @param launchRefusals `GameCommandChannel.launchRefusals`, passed in rather than read here so
     *   the mapper stays a pure function of the board plus one int (and JVM-testable without the
     *   channel).
     */
    fun update(launchRefusals: Int): HudState? {
        var dirty = !published

        val score = session.totalScore
        val dropSubtotal = session.dropSubtotal
        val ballsRemaining = session.remainingBalls
        val ballsInFlight = session.activeBallsInFlight
        val complete = session.isLevelComplete
        val failed = session.isLevelFailed

        if (score != lastScore ||
            dropSubtotal != lastDropSubtotal ||
            ballsRemaining != lastBallsRemaining ||
            ballsInFlight != lastBallsInFlight ||
            complete != lastComplete ||
            failed != lastFailed ||
            launchRefusals != lastRefusals
        ) {
            dirty = true
        }

        // Only walk the candy list when a CLEAR_COLOR objective actually needs the dirty key.
        val activeCandies = if (hasClearColor) countActiveCandies() else 0
        val candiesChanged = activeCandies != lastActiveCandies
        lastActiveCandies = activeCandies

        for (i in 0 until objectiveCount) {
            val def = defs[i]
            val clearColor = def.type == ObjectiveType.CLEAR_COLOR
            // The only allocating branch, and only when a candy actually left the board.
            if (clearColor && published && !candiesChanged) continue

            val current = if (clearColor) clearColorProgress(def, i) else tracker.getProgress(def)
            if (current != progress[i]) {
                progress[i] = current
                dirty = true
            }
            val done = tracker.isComplete(def, boardState)
            if (done != completed[i]) {
                completed[i] = done
                dirty = true
            }
        }

        if (!dirty) return null

        lastScore = score
        lastDropSubtotal = dropSubtotal
        lastBallsRemaining = ballsRemaining
        lastBallsInFlight = ballsInFlight
        lastComplete = complete
        lastFailed = failed
        lastRefusals = launchRefusals
        published = true

        val objectives = ArrayList<HudObjective>(objectiveCount)
        for (i in 0 until objectiveCount) {
            val def = defs[i]
            objectives += HudObjective(
                type = def.type,
                color = def.color,
                gem = def.gem,
                chainLength = def.chain,
                current = progress[i],
                target = targets[i],
                complete = completed[i],
            )
        }

        return HudState(
            levelId = level.id,
            levelCode = level.code,
            world = level.world,
            score = score,
            dropSubtotal = dropSubtotal,
            ballsRemaining = ballsRemaining,
            ballsTotal = level.balls,
            ballsInFlight = ballsInFlight,
            // Mirrors LevelSession.launchBall's own guard, so the HUD and the session agree.
            canLaunch = ballsRemaining > 0 && ballsInFlight == 0 && !complete && !failed,
            status = when {
                complete -> LevelStatus.COMPLETE
                failed -> LevelStatus.FAILED
                else -> LevelStatus.PLAYING
            },
            launchRefusals = launchRefusals,
            objectives = objectives,
        )
    }

    /**
     * The finished attempt, read from the session's authoritative fields.
     *
     * Crowns come from [CrownCalculator] against `remainingBalls` and `isLevelComplete` rather
     * than from the `LevelCompleted` event, which a board-clearing Sugar Storm may have pushed out
     * of the 256-entry `DROP_OLDEST` buffer. Call once, at the terminal transition: it performs the
     * attempt's single `collectedByColor` read.
     */
    fun outcome(): LevelOutcome {
        val won = session.isLevelComplete
        return LevelOutcome(
            levelId = level.id,
            won = won,
            crowns = CrownCalculator.calculateCrowns(level, session.remainingBalls, won),
            score = session.totalScore,
            ballsRemaining = session.remainingBalls,
            collected = session.collectedByColor,
        )
    }

    private fun clearColorProgress(def: ObjectiveDef, index: Int): Int {
        val color = def.color ?: return targets[index]
        val remaining = boardState.getCandiesByColor(color).size
        return (targets[index] - remaining).coerceAtLeast(0)
    }

    private fun targetFor(def: ObjectiveDef): Int = when (def.type) {
        ObjectiveType.SCORE -> def.score ?: 1
        ObjectiveType.CLEAR_COLOR -> def.color?.let { boardState.getCandiesByColor(it).size } ?: 0
        ObjectiveType.COLLECT_CANDY,
        ObjectiveType.COLLECT_GEM,
        ObjectiveType.CHAIN,
        ObjectiveType.CUP,
        -> def.count ?: 1
    }

    /** Index loop, no iterator, no lambda — this runs every frame when a CLEAR_COLOR is present. */
    private fun countActiveCandies(): Int {
        val candies = boardState.candies
        var n = 0
        for (i in candies.indices) {
            if (candies[i].active) n++
        }
        return n
    }
}
