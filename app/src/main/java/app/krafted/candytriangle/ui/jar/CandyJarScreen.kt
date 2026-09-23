package app.krafted.candytriangle.ui.jar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.krafted.candytriangle.R
import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.TrailType
import app.krafted.candytriangle.ui.theme.CandyBlue
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyPurple
import app.krafted.candytriangle.ui.theme.CandyRose
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingFaint
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.LockedGate
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.NightVoid
import app.krafted.candytriangle.ui.theme.StatusSuccess

/**
 * The Candy Jar screen (PRD §7): four lifetime colour jars with an animated fill, their three
 * reward tiers, and the skin/trail selector.
 *
 * Read-only with respect to progress. Candies are banked by `GameViewModel` at the end of an
 * attempt (plan deviation D1-b — D4's results screen must display what D1 wrote, not re-bank), so
 * this screen never calls `recordLevelResult` or `bankCandies`; the only writes it makes are the
 * two cosmetic equips, both gated by [JarViewModel].
 *
 * Deliberately thin. §11's suite is JVM-only, so nothing here may decide a number — every tier,
 * threshold, fraction and tick comes pre-computed out of [JarUiStateMapper], which is unit-tested.
 * What is left is layout, and compilation is the proof of that.
 */
@Composable
fun CandyJarScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: JarViewModel = jarViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CandyJarContent(
        state = state,
        onBack = onBack,
        onEquipSkin = viewModel::equipSkin,
        onEquipTrail = viewModel::equipTrail,
        modifier = modifier,
    )
}

/**
 * Builds the destination-scoped [JarViewModel] out of the app-scoped `AppContainer`.
 *
 * `Context.appContainer` reaches it through `applicationContext`, so the resolved container can
 * never outlive-leak an Activity; `remember` keyed on the context keeps recomposition from
 * rebuilding a factory that `viewModel()` would only ignore anyway.
 */
@Composable
private fun jarViewModel(): JarViewModel {
    val context = LocalContext.current
    return viewModel(factory = remember(context) { JarViewModel.factory(context) })
}

/** The stateless half, so the previews below can render the screen without an `AppContainer`. */
@Composable
internal fun CandyJarContent(
    state: CandyJarUiState,
    onBack: () -> Unit,
    onEquipSkin: (BallSkin) -> Unit,
    onEquipTrail: (TrailType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NightVoid)
            // The activity is edge-to-edge and sticky-immersive, so the bars can reappear
            // transiently; pad for them rather than letting the title slide under the status bar.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.size(8.dp))
            Column {
                Text(
                    text = stringResource(R.string.label_candy_jar),
                    style = MaterialTheme.typography.headlineMedium,
                    color = IcingWhite,
                )
                Text(
                    text = stringResource(R.string.jar_total_format, state.totalCandies),
                    style = MaterialTheme.typography.labelMedium,
                    color = IcingDim,
                )
            }
        }

        Text(
            text = stringResource(R.string.jar_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = IcingFaint,
        )

        // Two rows of two rather than a LazyVerticalGrid: there are exactly four jars, forever,
        // and nesting a lazy grid inside a scrolling Column is an intrinsic-measurement crash.
        state.jars.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pair.forEach { jar ->
                    JarPanel(jar = jar, modifier = Modifier.weight(1f))
                }
                // Keeps a hypothetical odd jar count from stretching to full width.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        CosmeticSelector(
            unlockedSkins = state.unlockedSkins,
            equippedSkin = state.equippedBallSkin,
            onEquipSkin = onEquipSkin,
            unlockedTrails = state.unlockedTrails,
            equippedTrail = state.equippedTrail,
            onEquipTrail = onEquipTrail,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/** One jar: the glass, the numbers, and its three §7 reward rows. */
@Composable
private fun JarPanel(jar: JarUiState, modifier: Modifier = Modifier) {
    val colorName = stringResource(jar.color.nameRes())
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NightSurface)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        JarCanvas(
            fillFraction = jar.fillFraction,
            tickFractions = jar.tickFractions,
            candyColor = jar.color.tint(),
            modifier = Modifier
                .size(width = 96.dp, height = 132.dp)
                .semantics {
                    contentDescription = jarDescription(colorName, jar)
                },
        )

        Text(
            text = stringResource(R.string.jar_name_format, colorName),
            style = MaterialTheme.typography.titleMedium,
            color = IcingWhite,
        )
        Text(
            text = stringResource(
                R.string.jar_count_format,
                jar.count,
                // The last reward's threshold is a full jar by definition.
                jar.rewards.last().threshold,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = jar.color.tint(),
        )
        Text(
            text = stringResource(R.string.jar_tier_format, jar.tier, JarUnlocks.MAX_TIER),
            style = MaterialTheme.typography.labelMedium,
            color = IcingDim,
        )

        // A plain two-Box bar rather than a LinearProgressIndicator: it needs no indeterminate
        // mode, no stop indicator and no track gap, and this cannot drift with Material's defaults.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(LockedGate),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(jar.fillFraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(jar.color.tint()),
            )
        }

        Text(
            text = jar.nextThreshold?.let {
                stringResource(R.string.jar_next_tier_format, jar.candiesToNextTier, jar.tier + 1)
            } ?: stringResource(R.string.jar_all_tiers),
            style = MaterialTheme.typography.labelSmall,
            color = if (jar.isComplete) StatusSuccess else IcingFaint,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(2.dp))

        jar.rewards.forEach { slot -> RewardRow(slot) }
    }
}

/**
 * One reward tier.
 *
 * Tier 3 is display-only: a Sweet Room is entered from the map, which is D3/D4 work, so an unlocked
 * B1..B4 row here is a badge, not a button.
 */
@Composable
private fun RewardRow(slot: RewardSlot) {
    val label = when (val reward = slot.reward) {
        is JarReward.Skin -> stringResource(
            R.string.jar_reward_skin_format,
            stringResource(reward.skin.nameRes()),
        )

        is JarReward.Trail -> stringResource(
            R.string.jar_reward_trail_format,
            stringResource(reward.trail.nameRes()),
        )

        is JarReward.SweetRoom -> stringResource(
            R.string.jar_reward_sweet_room_format,
            reward.code,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (slot.unlocked) CandyGold else LockedGate),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.unlocked) IcingWhite else IcingFaint,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.jar_reward_threshold_format, slot.threshold),
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.unlocked) CandyGold else IcingFaint,
        )
    }
}

private fun jarDescription(colorName: String, jar: JarUiState): String =
    "$colorName jar, ${jar.count} candies, tier ${jar.tier} of ${JarUnlocks.MAX_TIER}"

private fun CandyColor.nameRes(): Int = when (this) {
    CandyColor.GREEN -> R.string.candy_name_green
    CandyColor.PURPLE -> R.string.candy_name_purple
    CandyColor.PINK -> R.string.candy_name_pink
    CandyColor.BLUE -> R.string.candy_name_blue
}

/** The branded §4.1 candy tints already in `ui/theme/Color.kt`; Pink's is `CandyRose`. */
private fun CandyColor.tint(): Color = when (this) {
    CandyColor.GREEN -> CandyGreen
    CandyColor.PURPLE -> CandyPurple
    CandyColor.PINK -> CandyRose
    CandyColor.BLUE -> CandyBlue
}

private fun BallSkin.nameRes(): Int = when (this) {
    BallSkin.DEFAULT -> R.string.skin_name_default
    BallSkin.GREEN -> R.string.skin_name_green
    BallSkin.PURPLE -> R.string.skin_name_purple
    BallSkin.BLUE -> R.string.skin_name_blue
    BallSkin.GOLD -> R.string.skin_name_gold
}

private fun TrailType.nameRes(): Int = when (this) {
    TrailType.NONE -> R.string.trail_name_none
    TrailType.GREEN -> R.string.trail_name_green
    TrailType.PURPLE -> R.string.trail_name_purple
    TrailType.PINK -> R.string.trail_name_pink
    TrailType.BLUE -> R.string.trail_name_blue
}

// ---------------------------------------------------------------------------
// Design documentation — see the note in JarCanvas.kt on why these are previews and not tests.
// ---------------------------------------------------------------------------

@Preview(name = "Candy Jar — fresh install", showBackground = true, heightDp = 900)
@Composable
private fun CandyJarFreshPreview() {
    CandyTriangleTheme {
        CandyJarContent(
            state = JarViewModel.INITIAL,
            onBack = {},
            onEquipSkin = {},
            onEquipTrail = {},
        )
    }
}

@Preview(name = "Candy Jar — mid progression", showBackground = true, heightDp = 900)
@Composable
private fun CandyJarProgressPreview() {
    CandyTriangleTheme {
        CandyJarContent(
            state = JarUiStateMapper.map(
                jarCounts = mapOf(
                    CandyColor.GREEN to 212,
                    CandyColor.PURPLE to 131,
                    CandyColor.PINK to 64,
                    CandyColor.BLUE to 12,
                ),
                equippedBallSkin = BallSkin.GOLD,
                equippedTrail = TrailType.PURPLE,
                jarConfig = null,
            ),
            onBack = {},
            onEquipSkin = {},
            onEquipTrail = {},
        )
    }
}
