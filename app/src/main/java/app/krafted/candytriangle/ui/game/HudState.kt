package app.krafted.candytriangle.ui.game

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.ObjectiveType

/**
 * Immutable, main-thread-safe snapshot of everything the Compose HUD draws.
 *
 * Produced by [HudStateMapper] **on the game thread**, inside
 * `GameLoopListener.onFrame`, and published through a `MutableStateFlow` — a `value =` write is
 * safe from any thread and gives the collecting main thread its happens-before edge. Nothing here
 * aliases a mutable engine object: every field is a primitive, an enum or an immutable list, so a
 * recomposition can never read a half-written `LevelSession` field.
 *
 * **No user-facing strings live here.** The HUD carries enums and ints and the composables format
 * them with `stringResource`, so the same snapshot survives a locale change and stays testable on
 * the JVM without a `Context`.
 *
 * Owner: Agent 2 (`ui/game`).
 */
data class HudState(
    val levelId: Int,
    /** `"B1"`..`"B4"` for a Sweet Room, `null` for a main level (§6.3 `code`). */
    val levelCode: String?,
    /** Panorama world 1..4, or 0 for a Sweet Room (§6.1). */
    val world: Int,
    /** `LevelSession.totalScore` — banked drops only; the live drop is [dropSubtotal]. */
    val score: Int,
    /** `LevelSession.dropSubtotal` — points earned in the drop currently in flight. */
    val dropSubtotal: Int,
    val ballsRemaining: Int,
    /** `LevelDef.balls`, the starting inventory; cup refunds can push [ballsRemaining] above it. */
    val ballsTotal: Int,
    val ballsInFlight: Int,
    /** Mirrors `LevelSession.launchBall`'s own guard, so the HUD can grey out the launcher. */
    val canLaunch: Boolean,
    val status: LevelStatus,
    /**
     * `GameCommandChannel.launchRefusals`. A refused launch is normal (§2's one-ball rule racing a
     * finger lift), never an error — the counter changing is the HUD's cue to shake the ball
     * counter, and nothing retries or queues the launch.
     */
    val launchRefusals: Int,
    val objectives: List<HudObjective>,
) {

    /** True while any ball of the current drop is still on the board. */
    val dropActive: Boolean get() = ballsInFlight > 0

    /** Banked score plus the drop in flight — what the player reads as "my score right now". */
    val displayScore: Int get() = score + dropSubtotal
}

/** Which of `LevelSession`'s two terminal flags is set, if either. */
enum class LevelStatus {
    PLAYING,
    COMPLETE,
    FAILED,
}

/**
 * One §6.3 objective, flattened for display.
 *
 * [current] and [target] are always numeric, including for [ObjectiveType.CLEAR_COLOR], which
 * `ObjectiveTracker` itself tracks as a live board predicate with no counter: [HudStateMapper]
 * gives it `target` = the candies of that colour placed at level build time and `current` = how
 * many of them have gone. [complete] always comes from `ObjectiveTracker.isComplete`, never from
 * `current >= target`, so the authority stays in one place.
 */
data class HudObjective(
    val type: ObjectiveType,
    val color: CandyColor?,
    val gem: GemType?,
    /** The chain length a [ObjectiveType.CHAIN] objective asks for (`ObjectiveDef.chain`). */
    val chainLength: Int?,
    val current: Int,
    val target: Int,
    val complete: Boolean,
) {

    /** 0..1 for a progress bar. A zero target is 1 when [complete], so a bar never divides by 0. */
    val fraction: Float
        get() = when {
            complete -> 1f
            target <= 0 -> 0f
            else -> (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * The result of one attempt, computed from **`LevelSession`'s authoritative fields** at the
 * terminal transition — never reconstructed from a `LevelCompleted` event, which `events`'
 * no-replay `DROP_OLDEST` contract does not guarantee to deliver.
 *
 * [collected] is the single read of `LevelSession.collectedByColor` per attempt: that getter builds
 * a fresh `LinkedHashMap` on every call, so it must never be touched per frame.
 *
 * **D1 deviation D1-b:** `GameViewModel` persists this through `ProgressStore.recordLevelResult`
 * and `bankCandies` as soon as it is computed. D4's results screen must **display** these numbers,
 * not bank them again.
 */
data class LevelOutcome(
    val levelId: Int,
    val won: Boolean,
    val crowns: Int,
    val score: Int,
    val ballsRemaining: Int,
    val collected: Map<CandyColor, Int>,
    /** The portion of [score] added by Sugar Rush; zero on a failed attempt. */
    val sugarRushBonus: Int,
)

/**
 * Transient chain garnish driven by the best-effort `events` stream (§4.1 chains and Sugar Pops).
 *
 * Purely decorative: a dropped event costs an animation, never a number. [sequence] increments on
 * every emission so an identical chain twice in a row still restarts the animation.
 *
 * Named `ChainCue` rather than `ChainBanner` so the composable that draws it can own that name.
 */
data class ChainCue(
    val color: CandyColor,
    val length: Int,
    val sugarPop: Boolean,
    val sequence: Long,
)

/** Transient "+N" garnish for a completed drop (§5.1 subtotal times the drop multiplier). */
data class DropFlash(
    val dropScore: Int,
    val multiplier: Int,
    val sequence: Long,
)
