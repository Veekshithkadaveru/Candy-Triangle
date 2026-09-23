package app.krafted.candytriangle.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The gameplay screen: a `GameSurfaceView` hosted in an `AndroidView`, with the Compose HUD
 * (objective header, ball counter, scoreboard, pause button) drawn over the surface hole.
 *
 * Owns a `GameViewModel` scoped to this navigation destination, so leaving the level calls
 * `onCleared()` — which is what stops the game thread and releases the `LevelBoard`.
 *
 * [onLevelFinished] fires exactly once per attempt, after `GameViewModel` has already written the
 * result through `ProgressStore.recordLevelResult` and `bankCandies` (D1 deviation D1-b: **D4's
 * results screen displays what D1 wrote, it must not bank again**). Its parameters are primitives
 * on purpose, so the navigation graph does not need a shared result type.
 *
 * Owner: Agent 2 (`ui/game`).
 */
@Composable
fun GameScreen(
    levelId: Int,
    onExit: () -> Unit,
    onLevelFinished: (levelId: Int, won: Boolean, crowns: Int, score: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // TODO("owner: Agent 2")
}
