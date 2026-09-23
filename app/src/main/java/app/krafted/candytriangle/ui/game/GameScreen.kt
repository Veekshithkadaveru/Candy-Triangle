package app.krafted.candytriangle.ui.game

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.krafted.candytriangle.R
import app.krafted.candytriangle.ui.board.AimMath
import app.krafted.candytriangle.ui.board.BoardTransform
import app.krafted.candytriangle.ui.board.GameCommandChannel
import app.krafted.candytriangle.ui.board.GameSurfaceView
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.StatusError

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
    val application = LocalContext.current.applicationContext
    val gameViewModel: GameViewModel = viewModel(
        key = "game-$levelId",
        factory = remember(application, levelId) { GameViewModel.factory(application, levelId) },
    )
    GameScreenContent(
        viewModel = gameViewModel,
        onExit = onExit,
        onLevelFinished = onLevelFinished,
        modifier = modifier,
    )
}

/**
 * The screen body, separated from [GameScreen] only so the ViewModel lookup and the UI are not
 * entangled.
 *
 * ### Why the surface is always mounted
 *
 * The `AndroidView` sits outside the loading / error branches and `attach`/`detach` is driven by a
 * `DisposableEffect` keyed on the ready state. That way a restart swaps the board on the *same*
 * surface, and leaving the screen always detaches exactly once — `detach()` joins the loop thread,
 * which is half of the answer to "the game thread must not outlive the level" (the other half is
 * the navigation-scoped ViewModel's `onCleared`).
 */
@Composable
private fun GameScreenContent(
    viewModel: GameViewModel,
    onExit: () -> Unit,
    onLevelFinished: (levelId: Int, won: Boolean, crowns: Int, score: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hud by viewModel.hud.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val chainCue by viewModel.chainBanner.collectAsStateWithLifecycle()
    val dropFlash by viewModel.dropFlash.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()

    val ready = uiState as? GameUiState.Ready
    val density = LocalDensity.current
    var transform by remember { mutableStateOf<BoardTransform?>(null) }
    var surfaceView by remember { mutableStateOf<GameSurfaceView?>(null) }
    var topInsetPx by remember { mutableIntStateOf(with(density) { HudHeaderHeight.roundToPx() }) }
    var bottomInsetPx by remember { mutableIntStateOf(with(density) { HudFooterHeight.roundToPx() }) }

    // Pause on ON_PAUSE, not ON_STOP: the loop must be parked before the window loses focus, or a
    // backgrounded level keeps a 60 Hz thread and the physics runs on while nobody is watching.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Binds the board to the surface. Re-keyed on `ready`, so a restart detaches the old board
    // before attaching the new one, and leaving the screen detaches unconditionally.
    DisposableEffect(surfaceView, ready) {
        val view = surfaceView
        if (view != null && ready != null) {
            view.attach(ready.board, viewModel.channel, viewModel, ready.worldIndex)
        }
        onDispose { view?.detach() }
    }

    // Fired once per attempt: the ViewModel holds the consumed flag, so a recomposition or a
    // configuration change cannot deliver the same result twice.
    LaunchedEffect(finished) {
        if (finished == null) return@LaunchedEffect
        val outcome = viewModel.consumeFinished() ?: return@LaunchedEffect
        onLevelFinished(outcome.levelId, outcome.won, outcome.crowns, outcome.score)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                GameSurfaceView(context).also { view ->
                    view.onTransformChanged = { transform = it }
                    surfaceView = view
                }
            },
            update = { view ->
                // The HUD's own measured heights, so the letterbox never puts board content under
                // the header or the footer. dp would be a guess; these are what was laid out.
                view.setBoardInsets(topInsetPx, bottomInsetPx)
            },
            modifier = Modifier
                .fillMaxSize()
                .aimGestures(
                    enabled = ready != null && !paused && finished == null,
                    transform = transform,
                    channel = viewModel.channel,
                    pivotX = ready?.pivotX ?: 0f,
                    pivotY = ready?.pivotY ?: 0f,
                    aimClampRadians = ready?.aimClampRadians ?: 0f,
                ),
        )

        // HUD chrome. Never opaque over the board: the SurfaceView composites below the window and
        // punches a transparent hole, so a solid container here would black the board out.
        Column(Modifier.fillMaxSize()) {
            hud?.let { snapshot ->
                ObjectiveHeader(
                    levelId = snapshot.levelId,
                    levelCode = snapshot.levelCode,
                    objectives = snapshot.objectives,
                    modifier = Modifier.onSizeChanged { topInsetPx = it.height },
                )
            }
            Spacer(Modifier.weight(1f))
            hud?.let { snapshot ->
                HudFooter(
                    hud = snapshot,
                    dropFlash = dropFlash,
                    modifier = Modifier.onSizeChanged { bottomInsetPx = it.height },
                )
            }
        }

        PauseButton(
            onClick = viewModel::pause,
            enabled = ready != null && !paused && finished == null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp),
        )

        ChainBanner(
            cue = chainCue,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
        )

        when (val state = uiState) {
            is GameUiState.Loading -> LoadingOverlay()
            is GameUiState.Error -> ErrorOverlay(state, onExit)
            is GameUiState.Ready -> Unit
        }

        if (paused) {
            GamePauseDialog(
                onResume = viewModel::resume,
                onRestart = viewModel::restart,
                onQuit = onExit,
            )
        }
    }
}

/**
 * Drag to aim, release to fire; tap to fire straight at the touch.
 *
 * Compose owns the gesture and the game thread owns the `Launcher`: everything here goes through
 * [GameCommandChannel]'s volatile scalars, which the loop drains at the top of a frame. That is
 * what leaves `Launcher` written by exactly one thread — and therefore what makes
 * `predictTrajectory`, which temporarily moves the world's oscillating pegs, safe.
 *
 * The pivot is the launcher's, `config.board.launcher.pivot` = **(500, 0)**, the triangle's apex —
 * not the spawn point (500, 75). Re-keyed on [transform], because every screen coordinate here is
 * mapped back through it.
 */
private fun Modifier.aimGestures(
    enabled: Boolean,
    transform: BoardTransform?,
    channel: GameCommandChannel,
    pivotX: Float,
    pivotY: Float,
    aimClampRadians: Float,
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(transform, enabled) {
            detectTapGestures { position ->
                val live = transform ?: return@detectTapGestures
                channel.setAim(
                    AimMath.aimForScreen(live, pivotX, pivotY, position.x, position.y, aimClampRadians),
                )
                // A refused launch is normal and is not retried: the loop notes it and the ball
                // counter shakes.
                channel.requestLaunch()
            }
        }
        .pointerInput(transform, enabled) {
            detectDragGestures(
                onDragStart = { channel.aiming = true },
                onDragCancel = { channel.aiming = false },
                onDragEnd = {
                    channel.aiming = false
                    channel.requestLaunch()
                },
            ) { change, _ ->
                val live = transform ?: return@detectDragGestures
                channel.setAim(
                    AimMath.aimForScreen(
                        live,
                        pivotX,
                        pivotY,
                        change.position.x,
                        change.position.y,
                        aimClampRadians,
                    ),
                )
                change.consume()
            }
        }
}

@Composable
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = CandyPink)
        Spacer(Modifier.padding(8.dp))
        Text(
            text = stringResource(R.string.game_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = IcingDim,
        )
    }
}

@Composable
private fun ErrorOverlay(
    state: GameUiState.Error,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (state.reason) {
                GameLoadError.LEVEL_NOT_FOUND ->
                    stringResource(R.string.game_error_level_not_found, state.levelId)

                GameLoadError.UNEXPECTED -> stringResource(R.string.game_error_unexpected)
            },
            style = MaterialTheme.typography.titleMedium,
            color = StatusError,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.action_back), color = IcingWhite)
        }
    }
}

@Preview(name = "Game HUD over the board", widthDp = 380, heightDp = 780, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun GameScreenHudPreview() {
    CandyTriangleTheme {
        val snapshot = previewHudState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                ObjectiveHeader(snapshot.levelId, snapshot.levelCode, snapshot.objectives)
                Spacer(Modifier.weight(1f))
                HudFooter(snapshot, dropFlash = null)
            }
            PauseButton(
                onClick = {},
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 4.dp, end = 8.dp),
            )
        }
    }
}
