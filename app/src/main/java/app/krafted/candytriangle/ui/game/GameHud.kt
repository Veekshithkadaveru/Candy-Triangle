package app.krafted.candytriangle.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.ui.theme.CandyBlue
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyPurple
import app.krafted.candytriangle.ui.theme.CandyRose
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightVoid
import app.krafted.candytriangle.ui.theme.StatusSuccess
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Compose HUD drawn over the `GameSurfaceView`'s transparent hole.
 *
 * **Every container in this file is transparent or a gradient that fades to transparent.** A plain
 * `SurfaceView` composites *below* the window and punches a hole through it, so an opaque HUD
 * background would black out the board entirely. That rule, not taste, is why the header and the
 * footer are gradients rather than surfaces.
 *
 * The composables are deliberately thin: they read a [HudState] and format it. §11's suite is
 * JVM-only, so nothing here can be unit-tested — all the arithmetic lives in [HudStateMapper],
 * which can. The `@Preview`s at the bottom are design documentation, not tests.
 *
 * Owner: Agent 2 (`ui/game`).
 */

// Scrim tints, private to this file: `ui/theme/Color.kt` is shared and frozen for D1/D2.
private val HudTopScrim = Color(0xE60B0518)
private val HudBottomScrim = Color(0xCC0B0518)
private val HudChipBackground = Color(0x66140A24)
private val HudProgressTrack = Color(0x33F5ECFF)

/** Nominal chrome heights — the board insets actually sent to A1 are the *measured* ones. */
internal val HudHeaderHeight = 132.dp
internal val HudFooterHeight = 104.dp

private const val SHAKE_CYCLES = 6f
private const val SHAKE_AMPLITUDE_DP = 5f

/**
 * Level identity plus one row per §6.3 objective.
 *
 * Drawn at the very top of the screen, so its measured height is the board's top inset.
 */
@Composable
fun ObjectiveHeader(
    levelId: Int,
    levelCode: String?,
    objectives: List<HudObjective>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(HudTopScrim, Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (levelCode != null) {
                stringResource(R.string.game_sweet_room_label, levelCode)
            } else {
                stringResource(R.string.game_level_label, levelId)
            },
            style = MaterialTheme.typography.labelLarge,
            color = IcingDim,
        )
        if (objectives.isEmpty()) {
            Text(
                text = stringResource(R.string.game_objective_none),
                style = MaterialTheme.typography.titleMedium,
                color = IcingWhite,
            )
        } else {
            objectives.forEach { objective -> ObjectiveRow(objective) }
        }
    }
}

@Composable
private fun ObjectiveRow(objective: HudObjective, modifier: Modifier = Modifier) {
    val label = objective.label()
    val progress = stringResource(
        R.string.game_objective_progress,
        objective.current.coerceAtMost(objective.target),
        objective.target,
    )
    val done = stringResource(R.string.cd_game_objective_complete)
    val description = if (objective.complete) "$label, $done" else "$label, $progress"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (objective.complete) StatusSuccess else IcingWhite,
                modifier = Modifier.weight(1f, fill = true),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = progress,
                style = MaterialTheme.typography.labelLarge,
                color = if (objective.complete) StatusSuccess else IcingDim,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { objective.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp),
            color = if (objective.complete) StatusSuccess else objective.accentColor(),
            trackColor = HudProgressTrack,
        )
    }
}

/**
 * Remaining balls.
 *
 * Shakes when [launchRefusals] increments: `LevelBoard.launch` returning null is normal — §2 allows
 * one ball in flight and a finger can lift into that race — so a refusal is feedback, never an
 * error, and is never retried or queued.
 */
@Composable
fun BallCounter(
    ballsRemaining: Int,
    ballsTotal: Int,
    launchRefusals: Int,
    modifier: Modifier = Modifier,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(launchRefusals) {
        if (launchRefusals <= 0) return@LaunchedEffect
        shake.snapTo(1f)
        shake.animateTo(0f, tween(durationMillis = 360, easing = LinearEasing))
    }
    val shakeDp = (sin(shake.value * SHAKE_CYCLES * PI).toFloat() * shake.value * SHAKE_AMPLITUDE_DP).dp
    val description = stringResource(R.string.cd_game_balls_remaining, ballsRemaining, ballsTotal)

    Row(
        modifier = modifier
            .offset(x = shakeDp)
            .background(HudChipBackground, RoundedCornerShape(percent = 50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(CandyPink, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.game_balls_of_total, ballsRemaining, ballsTotal),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (ballsRemaining <= 0) CandyRose else IcingWhite,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.label_balls),
            style = MaterialTheme.typography.labelMedium,
            color = IcingDim,
        )
    }
}

/**
 * Banked score, plus the drop currently in flight.
 *
 * [dropSubtotal] is shown separately rather than added in: §5.1 multiplies the subtotal by the
 * drop multiplier only once the drop ends, so folding it into the total early would show a number
 * that then jumps.
 */
@Composable
fun ScoreBoard(
    score: Int,
    dropSubtotal: Int,
    dropFlash: DropFlash?,
    modifier: Modifier = Modifier,
) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(dropFlash?.sequence) {
        if (dropFlash == null) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(durationMillis = 1_100, easing = LinearEasing))
    }

    Column(
        modifier = modifier
            .background(HudChipBackground, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = stringResource(R.string.label_score),
            style = MaterialTheme.typography.labelMedium,
            color = IcingDim,
        )
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = IcingWhite,
        )
        when {
            dropSubtotal > 0 -> Text(
                text = stringResource(R.string.game_score_pending, dropSubtotal),
                style = MaterialTheme.typography.labelLarge,
                color = CandyGold,
            )

            dropFlash != null && flash.value > 0f -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (dropFlash.multiplier > 1) {
                    Text(
                        text = stringResource(R.string.game_drop_multiplier, dropFlash.multiplier),
                        style = MaterialTheme.typography.labelMedium,
                        color = CandyPink,
                        modifier = Modifier.alpha(flash.value),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(R.string.game_drop_bonus, dropFlash.dropScore),
                    style = MaterialTheme.typography.labelLarge,
                    color = CandyGold,
                    modifier = Modifier.alpha(flash.value),
                )
            }
        }
    }
}

/** Freezes the board (`channel.paused = true`) and opens [GamePauseDialog]. */
@Composable
fun PauseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tint = if (enabled) IcingWhite else IcingDim
    val description = stringResource(R.string.cd_game_pause)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        // `semantics`, not `clearAndSetSemantics`: IconButton applies its `clickable` *after* this
        // modifier on the same node, and a clearing node discards everything after it in the chain
        // — the click action and Role.Button with it. The two drawn bars carry no semantics.
        modifier = modifier.semantics { contentDescription = description },
    ) {
        // Drawn rather than loaded: no pause drawable ships in the asset pack, and D1 may not add
        // resources to the shared `res/drawable`.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(width = 5.dp, height = 18.dp).background(tint, RoundedCornerShape(2.dp)))
            Box(Modifier.size(width = 5.dp, height = 18.dp).background(tint, RoundedCornerShape(2.dp)))
        }
    }
}

/**
 * The §4.1 chain / Sugar Pop cue.
 *
 * Driven entirely by the best-effort `events` stream, so a dropped event costs this animation and
 * nothing else — no number on screen is derived from it.
 */
@Composable
fun ChainBanner(cue: ChainCue?, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(cue?.sequence) {
        if (cue == null) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        alpha.snapTo(1f)
        alpha.animateTo(0f, tween(durationMillis = 1_200, easing = LinearEasing))
    }
    if (cue == null || alpha.value <= 0f) return

    Text(
        text = if (cue.sugarPop) {
            stringResource(R.string.game_sugar_pop_banner, cue.length)
        } else {
            stringResource(R.string.game_chain_banner, cue.length)
        },
        style = MaterialTheme.typography.headlineMedium,
        color = cue.color.tint(),
        textAlign = TextAlign.Center,
        modifier = modifier
            .alpha(alpha.value)
            .background(HudChipBackground, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** The bottom strip: ball counter on the left, scoreboard on the right. */
@Composable
internal fun HudFooter(
    hud: HudState,
    dropFlash: DropFlash?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, HudBottomScrim)))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BallCounter(
            ballsRemaining = hud.ballsRemaining,
            ballsTotal = hud.ballsTotal,
            launchRefusals = hud.launchRefusals,
        )
        ScoreBoard(score = hud.score, dropSubtotal = hud.dropSubtotal, dropFlash = dropFlash)
    }
}

// ------------------------------------------------------------------ formatting

/**
 * §6.3's objective vocabulary as display copy.
 *
 * Deliberately not on [HudObjective]: the snapshot crosses a thread boundary 60 times a second and
 * carries enums and ints only, so a locale change costs a recomposition rather than a new frame.
 */
@Composable
private fun HudObjective.label(): String = when (type) {
    ObjectiveType.COLLECT_CANDY -> {
        val candyColor = color
        if (candyColor != null) {
            stringResource(
                R.string.game_objective_collect_candy_color,
                target,
                stringResource(candyColor.labelRes()),
            )
        } else {
            stringResource(R.string.game_objective_collect_candy_any, target)
        }
    }

    ObjectiveType.SCORE -> stringResource(R.string.game_objective_score, target)

    ObjectiveType.COLLECT_GEM -> {
        val gemType = gem
        if (gemType != null) {
            // The gem's name agrees with the count ("Smash 2 Blast Gems", "Smash 1 Sugar Storm"),
            // exactly as the intro dialog words the same objective.
            stringResource(
                R.string.game_objective_collect_gem_type,
                target,
                pluralStringResource(gemType.countedNameRes(), target),
            )
        } else {
            stringResource(R.string.game_objective_collect_gem_any, target)
        }
    }

    // `color` is required for CLEAR_COLOR — `LevelDef`'s mapper drops the objective without it —
    // so the elvis branch is unreachable in shipped data and exists only to stay total.
    ObjectiveType.CLEAR_COLOR -> stringResource(
        R.string.game_objective_clear_color,
        stringResource((color ?: CandyColor.PINK).labelRes()),
    )

    ObjectiveType.CHAIN -> {
        val length = chainLength ?: 1
        if (target > 1) {
            stringResource(R.string.game_objective_chain_repeat, length, target)
        } else {
            stringResource(R.string.game_objective_chain, length)
        }
    }

    ObjectiveType.CUP -> stringResource(R.string.game_objective_cup, target)
}

private fun HudObjective.accentColor(): Color = color?.tint() ?: CandyGold

private fun CandyColor.tint(): Color = when (this) {
    CandyColor.GREEN -> CandyGreen
    CandyColor.PURPLE -> CandyPurple
    CandyColor.PINK -> CandyRose
    CandyColor.BLUE -> CandyBlue
}

private fun CandyColor.labelRes(): Int = when (this) {
    CandyColor.GREEN -> R.string.candy_name_green
    CandyColor.PURPLE -> R.string.candy_name_purple
    CandyColor.PINK -> R.string.candy_name_pink
    CandyColor.BLUE -> R.string.candy_name_blue
}

/**
 * §4.2's gem names by count. Read-only use of the intro's `intro_gem_count_*` plurals, so the HUD
 * and `LevelIntroDialog` word an objective identically and there is one set of plural names to
 * translate. The singular `gem_name_*` read "Smash 2 Blast Gem".
 */
private fun GemType.countedNameRes(): Int = when (this) {
    GemType.SWEET -> R.plurals.intro_gem_count_sweet
    GemType.BLAST -> R.plurals.intro_gem_count_blast
    GemType.LINE -> R.plurals.intro_gem_count_line
    GemType.SPLIT -> R.plurals.intro_gem_count_split
    GemType.EXTRA_BALL -> R.plurals.intro_gem_count_extra_ball
    GemType.MAGNET -> R.plurals.intro_gem_count_magnet
    GemType.SUGAR_STORM -> R.plurals.intro_gem_count_sugar_storm
}

// ------------------------------------------------------------------ previews

/** A realistic mid-level snapshot, shared by the previews in this file and in `GameScreen.kt`. */
internal fun previewHudState(): HudState = HudState(
    levelId = 14,
    levelCode = null,
    world = 2,
    score = 12_450,
    dropSubtotal = 320,
    ballsRemaining = 7,
    ballsTotal = 10,
    ballsInFlight = 1,
    canLaunch = false,
    status = LevelStatus.PLAYING,
    launchRefusals = 0,
    objectives = listOf(
        HudObjective(ObjectiveType.COLLECT_CANDY, CandyColor.PINK, null, null, 9, 14, false),
        HudObjective(ObjectiveType.CLEAR_COLOR, CandyColor.BLUE, null, null, 6, 6, true),
        HudObjective(ObjectiveType.CUP, null, null, null, 1, 2, false),
    ),
)

@Preview(name = "Objective header", widthDp = 380, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun ObjectiveHeaderPreview() {
    CandyTriangleTheme {
        Box(Modifier.background(NightVoid)) {
            val hud = previewHudState()
            ObjectiveHeader(hud.levelId, hud.levelCode, hud.objectives)
        }
    }
}

@Preview(name = "HUD footer", widthDp = 380, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun HudFooterPreview() {
    CandyTriangleTheme {
        Box(Modifier.background(NightVoid)) {
            HudFooter(previewHudState(), DropFlash(dropScore = 1_800, multiplier = 5, sequence = 1L))
        }
    }
}

@Preview(name = "Chain banner", widthDp = 380, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun ChainBannerPreview() {
    CandyTriangleTheme {
        Box(Modifier.background(NightVoid).padding(24.dp)) {
            ChainBanner(ChainCue(CandyColor.PURPLE, length = 4, sugarPop = false, sequence = 1L))
        }
    }
}

@Preview(name = "Pause button", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun PauseButtonPreview() {
    CandyTriangleTheme {
        Box(Modifier.background(NightVoid)) { PauseButton(onClick = {}) }
    }
}
