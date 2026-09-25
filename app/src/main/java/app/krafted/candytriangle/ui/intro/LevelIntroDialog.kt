package app.krafted.candytriangle.ui.intro

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.ui.components.candyDialogEntrance
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.DialogScrim
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightOutlineDim
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.NightSurfaceHigh
import app.krafted.candytriangle.ui.theme.NightVoid
import app.krafted.candytriangle.ui.theme.OnCandyGold
import app.krafted.candytriangle.ui.theme.OnCandyPink
import app.krafted.candytriangle.ui.theme.WorldPegTints

/**
 * The pre-level dialog opened from a map node (D3): objectives with icons, balls, and the §5.3
 * crown thresholds, with Play and dismiss.
 *
 * FROZEN SIGNATURE (D3 lead's skeleton) — `LevelMapScreen` calls exactly this. [onPlay] starts the
 * level; [onDismiss] is a back press, an outside tap or the close button.
 *
 * A Compose `Dialog` with the platform default width turned off, so the card sets its own: 92% of
 * the screen, capped for tablets. The window then spans the screen, but Compose judges an outside
 * tap against the content's own bounds, so the margins beside the card still dismiss. The goals,
 * crowns and gem row scroll between a fixed header and a fixed Play row, so Play stays on screen
 * even on a 360 x 640 dp phone with the system bars showing.
 *
 * Deliberately thin, like `GameHud` and `CandyJarScreen`: every number and flag arrives decided in
 * [LevelIntroUiState] (see `LevelIntroMapper`, which is unit-tested); this file only lays out and
 * words them. The `@Preview`s at the bottom are design documentation, not tests.
 *
 * Owner: D3 Agent C (intro).
 */
@Composable
fun LevelIntroDialog(
    state: LevelIntroUiState,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        IntroCard(
            state = state,
            onPlay = onPlay,
            onDismiss = onDismiss,
            modifier = modifier.candyDialogEntrance(),
        )
    }
}

private const val CARD_WIDTH_FRACTION = 0.92f
private const val MAX_CROWNS = 3

private val CardMaxWidth = 420.dp
private val CardShape = RoundedCornerShape(24.dp)
private val PillShape = RoundedCornerShape(percent = 50)
private val ObjectiveIconSize = 36.dp
private val CrownIconSize = 20.dp
private val CrownGap = 3.dp
private val CrownSlotWidth = CrownIconSize * MAX_CROWNS + CrownGap * (MAX_CROWNS - 1)
private val GemIconSize = 42.dp
private val GemHaloSize = 52.dp
private val GemCellWidth = 64.dp

/** Room under a gem for the lower part of its "New" pill, kept on every cell so they align. */
private val GemBadgeOverhang = 4.dp

/**
 * Gold, held nearly flat out to the sprite's edge (42 / 52 of the radius) and only then fading, so
 * what shows around the gem is a ring of glow rather than a gradient hidden behind it.
 */
private val NewGemHalo = Brush.radialGradient(
    0f to CandyGold.copy(alpha = 0.6f),
    0.78f to CandyGold.copy(alpha = 0.45f),
    1f to Color.Transparent,
)

/** The card's horizontal inset; the scrolling body and the header share it. */
private val CardPadding = 20.dp

/**
 * The card inside the dialog window, and the whole of the previews below — a `Dialog` opens its own
 * window, which a static preview cannot render reliably.
 */
@Composable
private fun IntroCard(
    state: LevelIntroUiState,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = accentFor(state)
    val title = introTitle(state)
    Column(
        modifier = modifier
            // widthIn before fillMaxWidth: the other way round, the fraction's exact width would
            // override the cap and a tablet would get a 92%-wide card.
            .widthIn(max = CardMaxWidth)
            .fillMaxWidth(CARD_WIDTH_FRACTION)
            .padding(vertical = 12.dp)
            .clip(CardShape)
            .background(NightSurface)
            .background(Brush.verticalGradient(0f to accent.copy(alpha = 0.16f), 0.3f to Color.Transparent))
            .border(1.5.dp, accent.copy(alpha = 0.6f), CardShape)
            .semantics { paneTitle = title },
    ) {
        IntroHeader(
            state = state,
            title = title,
            accent = accent,
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 18.dp, bottom = 10.dp),
        )
        Column(
            modifier = Modifier
                // fill = false: a short intro stays short, a tall one scrolls instead of pushing
                // the Play row off the bottom of the window.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = CardPadding, end = CardPadding, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GoalsSection(state.objectives)
            CrownsSection(state)
            if (state.gems.isNotEmpty() || state.hasMovingRows) BoardSection(state, accent)
        }
        HorizontalDivider(thickness = 1.dp, color = NightOutlineDim)
        ActionRow(
            onPlay = onPlay,
            onDismiss = onDismiss,
            modifier = Modifier.padding(start = 12.dp, end = CardPadding, top = 10.dp, bottom = 14.dp),
        )
    }
}

// ------------------------------------------------------------------ header

/** "World 2 · Velvet Swirl" over "Level 12" (or "Bonus level" over "Sweet Room B1"), balls beside. */
@Composable
private fun IntroHeader(
    state: LevelIntroUiState,
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val worldName = worldNameRes(state.world)
    val eyebrow = when {
        state.isSweetRoom -> stringResource(R.string.intro_bonus_label)
        worldName != null -> stringResource(R.string.intro_world_format, state.world, stringResource(worldName))
        else -> null
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(text = eyebrow, style = MaterialTheme.typography.labelLarge, color = accent)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = IcingWhite,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.width(12.dp))
        BallsChip(state.balls)
    }
}

/**
 * The starting ball count as a badge beside the title. Number over label, not side by side: at
 * 360 dp the narrower badge is what keeps "Sweet Room B1" on one line.
 */
@Composable
private fun BallsChip(balls: Int) {
    Row(
        modifier = Modifier
            .background(NightSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BallSprite(Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = balls.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = IcingWhite,
            )
            Text(
                text = stringResource(R.string.label_balls),
                style = MaterialTheme.typography.labelSmall,
                color = IcingDim,
            )
        }
    }
}

// ------------------------------------------------------------------ goals

@Composable
private fun GoalsSection(objectives: List<IntroObjective>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.intro_section_goals))
        if (objectives.isEmpty()) {
            // The HUD's copy for an objective-less level, so the two screens agree.
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
private fun ObjectiveRow(objective: IntroObjective) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ObjectiveIcon(objective, Modifier.size(ObjectiveIconSize))
        Spacer(Modifier.width(12.dp))
        Text(
            text = objective.label(),
            style = MaterialTheme.typography.titleMedium,
            color = IcingWhite,
        )
    }
}

// ------------------------------------------------------------------ crowns (§5.3)

/**
 * The three §5.3 tiers as rows — one crown, two, three — each lit (`coin.png`) once earned, plus
 * the player's best score beside the heading once there is one.
 */
@Composable
private fun CrownsSection(state: LevelIntroUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.label_crowns), Modifier.weight(1f))
            if (state.bestScore > 0) BestScore(state.bestScore)
        }
        for (tier in 1..MAX_CROWNS) {
            val requirement = when (tier) {
                1 -> stringResource(R.string.intro_crown_tier_clear)
                2 -> ballsLeftRequirement(state.twoCrownBalls)
                else -> ballsLeftRequirement(state.threeCrownBalls)
            }
            CrownTierRow(tier = tier, earned = state.crownsEarned >= tier, requirement = requirement)
        }
    }
}

@Composable
private fun ballsLeftRequirement(balls: Int): String =
    if (balls <= 0) {
        // A zero threshold is met by any clear; "Finish with 0+ balls left" would say the same,
        // badly.
        stringResource(R.string.intro_crown_tier_clear)
    } else {
        pluralStringResource(R.plurals.intro_crown_tier_balls, balls, balls)
    }

@Composable
private fun CrownTierRow(tier: Int, earned: Boolean, requirement: String) {
    val description = stringResource(
        R.string.intro_cd_crown_tier,
        pluralStringResource(R.plurals.intro_cd_crowns, tier, tier),
        requirement,
        stringResource(if (earned) R.string.cd_crown_earned else R.string.cd_crown_locked),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(CrownSlotWidth),
            horizontalArrangement = Arrangement.spacedBy(CrownGap),
        ) {
            repeat(tier) { CrownSprite(earned, Modifier.size(CrownIconSize)) }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = requirement,
            style = MaterialTheme.typography.bodyMedium,
            color = if (earned) IcingWhite else IcingDim,
        )
    }
}

@Composable
private fun BestScore(bestScore: Int) {
    Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = stringResource(R.string.label_best),
            style = MaterialTheme.typography.labelMedium,
            color = IcingDim,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = bestScore.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = CandyGold,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

// ------------------------------------------------------------------ the board

/** The gem row, each gem "New" on its §4.2 intro level, and the moving-rows chip. */
@Composable
private fun BoardSection(state: LevelIntroUiState, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.intro_section_board))
        if (state.gems.isNotEmpty()) {
            // A FlowRow, not a Row: the shipped boards carry at most four gems, which fit a
            // 360 dp phone on one line, but a config may place all seven.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.gems.forEach { gem -> GemCell(gem, isNew = gem in state.newGems) }
            }
        }
        if (state.hasMovingRows) MovingPegsChip(accent)
    }
}

/**
 * One gem of the row. A new gem gets a gold halo and a "New" pill; the one-time gem-intro popup
 * that explains it (and `ProgressStore.markGemIntroSeen`) is D4's.
 */
@Composable
private fun GemCell(gem: GemType, isNew: Boolean) {
    val name = stringResource(gem.nameRes())
    val description = if (isNew) stringResource(R.string.intro_cd_gem_new, name) else name
    Column(
        modifier = Modifier
            .width(GemCellWidth)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.BottomCenter) {
            // The halo box is larger than the sprite: a background only paints inside its own
            // bounds, and one the sprite's size would sit entirely behind the opaque gem.
            val halo = if (isNew) Modifier.background(NewGemHalo, CircleShape) else Modifier
            Box(
                modifier = Modifier
                    .padding(bottom = GemBadgeOverhang)
                    .size(GemHaloSize)
                    .then(halo),
                contentAlignment = Alignment.Center,
            ) {
                GemSprite(gem = gem, modifier = Modifier.size(GemIconSize))
            }
            if (isNew) NewPill()
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isNew) IcingWhite else IcingDim,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun NewPill() {
    Text(
        text = stringResource(R.string.intro_gem_new),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = OnCandyGold,
        modifier = Modifier
            .background(CandyGold, PillShape)
            .padding(horizontal = 7.dp),
    )
}

/** §3.3's oscillating rows (World 3+), in the world's tint — the same tint as the pegs themselves. */
@Composable
private fun MovingPegsChip(accent: Color) {
    Row(
        modifier = Modifier
            .background(NightSurfaceHigh, PillShape)
            .border(1.dp, accent.copy(alpha = 0.45f), PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovingPegsGlyph(color = accent, modifier = Modifier.size(width = 32.dp, height = 16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.intro_moving_pegs),
            style = MaterialTheme.typography.labelLarge,
            color = IcingWhite,
        )
    }
}

// ------------------------------------------------------------------ actions

@Composable
private fun ActionRow(onPlay: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(R.string.action_close), color = IcingDim)
        }
        Button(
            onClick = onPlay,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = PillShape,
            colors = ButtonDefaults.buttonColors(containerColor = CandyPink, contentColor = OnCandyPink),
        ) {
            Text(
                text = stringResource(R.string.action_play),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = IcingDim,
        modifier = modifier.semantics { heading() },
    )
}

// ------------------------------------------------------------------ formatting

/** "Level 12" or "Sweet Room B1" — the HUD's own labels, so the two screens name a level alike. */
@Composable
private fun introTitle(state: LevelIntroUiState): String {
    val code = state.levelCode
    return if (code != null) {
        stringResource(R.string.game_sweet_room_label, code)
    } else {
        stringResource(R.string.game_level_label, state.levelId)
    }
}

/**
 * §6.3's objective vocabulary as display copy, through the HUD's own `game_objective_*` templates.
 * The one departure is a typed gem's name, which is pluralised by count ("Smash 2 Blast Gems").
 */
@Composable
private fun IntroObjective.label(): String = when (type) {
    ObjectiveType.COLLECT_CANDY -> {
        val candy = color
        if (candy != null) {
            stringResource(R.string.game_objective_collect_candy_color, target, stringResource(candy.nameRes()))
        } else {
            stringResource(R.string.game_objective_collect_candy_any, target)
        }
    }

    ObjectiveType.SCORE -> stringResource(R.string.game_objective_score, target)

    ObjectiveType.COLLECT_GEM -> {
        val gemType = gem
        if (gemType != null) {
            stringResource(
                R.string.game_objective_collect_gem_type,
                target,
                pluralStringResource(gemType.countNameRes(), target),
            )
        } else {
            stringResource(R.string.game_objective_collect_gem_any, target)
        }
    }

    // `color` is required for CLEAR_COLOR, so the "candy" fallback only keeps this total.
    ObjectiveType.CLEAR_COLOR -> {
        val candy = color
        stringResource(
            R.string.game_objective_clear_color,
            if (candy != null) stringResource(candy.nameRes()) else stringResource(R.string.intro_candy_any),
        )
    }

    ObjectiveType.CHAIN ->
        if (times > 1) {
            stringResource(R.string.game_objective_chain_repeat, target, times)
        } else {
            stringResource(R.string.game_objective_chain, target)
        }

    ObjectiveType.CUP -> stringResource(R.string.game_objective_cup, target)
}

/** A main level wears its world's §6.1 peg tint; a Sweet Room, which has no world, the brand pink. */
private fun accentFor(state: LevelIntroUiState): Color =
    if (state.isSweetRoom) CandyPink else WorldPegTints.getOrNull(state.world - 1) ?: CandyPink

@StringRes
private fun worldNameRes(world: Int): Int? = when (world) {
    1 -> R.string.world_1_name
    2 -> R.string.world_2_name
    3 -> R.string.world_3_name
    4 -> R.string.world_4_name
    else -> null
}

@StringRes
private fun CandyColor.nameRes(): Int = when (this) {
    CandyColor.GREEN -> R.string.candy_name_green
    CandyColor.PURPLE -> R.string.candy_name_purple
    CandyColor.PINK -> R.string.candy_name_pink
    CandyColor.BLUE -> R.string.candy_name_blue
}

@StringRes
private fun GemType.nameRes(): Int = when (this) {
    GemType.SWEET -> R.string.gem_name_sweet
    GemType.BLAST -> R.string.gem_name_blast
    GemType.LINE -> R.string.gem_name_line
    GemType.SPLIT -> R.string.gem_name_split
    GemType.EXTRA_BALL -> R.string.gem_name_extra_ball
    GemType.MAGNET -> R.string.gem_name_magnet
    GemType.SUGAR_STORM -> R.string.gem_name_sugar_storm
}

@PluralsRes
private fun GemType.countNameRes(): Int = when (this) {
    GemType.SWEET -> R.plurals.intro_gem_count_sweet
    GemType.BLAST -> R.plurals.intro_gem_count_blast
    GemType.LINE -> R.plurals.intro_gem_count_line
    GemType.SPLIT -> R.plurals.intro_gem_count_split
    GemType.EXTRA_BALL -> R.plurals.intro_gem_count_extra_ball
    GemType.MAGNET -> R.plurals.intro_gem_count_magnet
    GemType.SUGAR_STORM -> R.plurals.intro_gem_count_sugar_storm
}

// ------------------------------------------------------------------ previews

/** L31-shaped: World 4, two objectives (§6.1), the Sugar Storm's intro level, moving rows. */
private fun previewWorldFourState() = LevelIntroUiState(
    levelId = 31,
    levelCode = null,
    world = 4,
    balls = 10,
    twoCrownBalls = 3,
    threeCrownBalls = 5,
    crownsEarned = 0,
    bestScore = 0,
    objectives = listOf(
        IntroObjective(ObjectiveType.COLLECT_GEM, null, GemType.SUGAR_STORM, target = 1, times = 1),
        IntroObjective(ObjectiveType.COLLECT_CANDY, null, null, target = 24, times = 1),
    ),
    gems = listOf(GemType.SWEET, GemType.BLAST, GemType.SUGAR_STORM),
    newGems = listOf(GemType.SUGAR_STORM),
    hasMovingRows = true,
)

/** B1-shaped: 15 balls, a score target, Sweet Room thresholds [4, 8], never played. */
private fun previewSweetRoomState() = LevelIntroUiState(
    levelId = 101,
    levelCode = "B1",
    world = 0,
    balls = 15,
    twoCrownBalls = 4,
    threeCrownBalls = 8,
    crownsEarned = 0,
    bestScore = 0,
    objectives = listOf(IntroObjective(ObjectiveType.SCORE, null, null, target = 6_500, times = 1)),
    gems = listOf(GemType.SWEET, GemType.BLAST),
    newGems = emptyList(),
    hasMovingRows = false,
)

/** L24-shaped and already cleared: two crowns lit, a best score, a repeated chain. */
private fun previewClearedState() = LevelIntroUiState(
    levelId = 24,
    levelCode = null,
    world = 3,
    balls = 10,
    twoCrownBalls = 3,
    threeCrownBalls = 5,
    crownsEarned = 2,
    bestScore = 18_450,
    objectives = listOf(
        IntroObjective(ObjectiveType.CHAIN, null, null, target = 3, times = 2),
        IntroObjective(ObjectiveType.COLLECT_CANDY, CandyColor.GREEN, null, target = 13, times = 1),
    ),
    gems = listOf(GemType.SWEET, GemType.SPLIT, GemType.EXTRA_BALL),
    newGems = emptyList(),
    hasMovingRows = true,
)

/** Behind a preview card: the map's night ground under the dialog scrim, as on a device. */
@Composable
private fun IntroPreviewFrame(content: @Composable () -> Unit) {
    CandyTriangleTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NightVoid)
                .background(DialogScrim),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Preview(name = "Intro — World 4, new gem (360 x 640)", widthDp = 360, heightDp = 640)
@Composable
private fun LevelIntroWorldFourPreview() {
    IntroPreviewFrame { IntroCard(previewWorldFourState(), onPlay = {}, onDismiss = {}) }
}

@Preview(name = "Intro — Sweet Room B1", widthDp = 360, heightDp = 640)
@Composable
private fun LevelIntroSweetRoomPreview() {
    IntroPreviewFrame { IntroCard(previewSweetRoomState(), onPlay = {}, onDismiss = {}) }
}

@Preview(name = "Intro — cleared, with a best score", widthDp = 360, heightDp = 640)
@Composable
private fun LevelIntroClearedPreview() {
    IntroPreviewFrame { IntroCard(previewClearedState(), onPlay = {}, onDismiss = {}) }
}

@Preview(name = "Intro — tablet width", widthDp = 840, heightDp = 600)
@Composable
private fun LevelIntroTabletPreview() {
    IntroPreviewFrame { IntroCard(previewClearedState(), onPlay = {}, onDismiss = {}) }
}
