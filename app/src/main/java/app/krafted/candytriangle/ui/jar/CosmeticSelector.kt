package app.krafted.candytriangle.ui.jar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.TrailType
import app.krafted.candytriangle.ui.theme.BallDefault
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
import app.krafted.candytriangle.ui.theme.NightSurfaceHigh

/**
 * The §7 skin and trail picker.
 *
 * Every skin and every trail is listed, locked ones included and visibly dimmed, because a reward
 * the player cannot yet see is a reward that cannot motivate them. A locked chip is not clickable;
 * `JarViewModel.equipSkin`/`equipTrail` refuse it a second time anyway, since
 * `ProgressStore.equipBallSkin`'s KDoc hands unlock validation to D2 and a UI-only gate would be a
 * single point of failure.
 *
 * Trails equipped here are copied to the game-thread command channel and drawn as a short
 * velocity-aligned candy streak by `BoardRenderer`.
 *
 * Stateless: all state in, two callbacks out.
 */
@Composable
fun CosmeticSelector(
    unlockedSkins: Set<BallSkin>,
    equippedSkin: BallSkin,
    onEquipSkin: (BallSkin) -> Unit,
    unlockedTrails: Set<TrailType>,
    equippedTrail: TrailType,
    onEquipTrail: (TrailType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(stringResource(R.string.jar_section_skins))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (skin in BallSkin.entries) {
                CosmeticChip(
                    label = skin.label(),
                    unlocked = skin in unlockedSkins,
                    equipped = skin == equippedSkin,
                    swatch = { Swatch(skin.tint()) },
                    onClick = { onEquipSkin(skin) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionHeader(stringResource(R.string.jar_section_trails))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (trail in TrailType.entries) {
                CosmeticChip(
                    label = trail.label(),
                    unlocked = trail in unlockedTrails,
                    equipped = trail == equippedTrail,
                    swatch = { TrailSwatch(trail.tint()) },
                    onClick = { onEquipTrail(trail) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = IcingDim,
    )
}

/**
 * One selectable cosmetic.
 *
 * A locked chip drops its `clickable` entirely rather than passing `enabled = false`, so it is not
 * reachable by keyboard or accessibility focus as an action either.
 */
@Composable
private fun CosmeticChip(
    label: String,
    unlocked: Boolean,
    equipped: Boolean,
    swatch: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        equipped -> MaterialTheme.colorScheme.primary
        unlocked -> MaterialTheme.colorScheme.outline
        else -> LockedGate
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NightSurfaceHigh.copy(alpha = if (unlocked) 1f else 0.4f))
            .border(
                width = if (equipped) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .then(
                if (unlocked) {
                    Modifier.clickable(role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { swatch() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (unlocked) IcingWhite else IcingFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                equipped -> stringResource(R.string.jar_equipped)
                unlocked -> ""
                else -> stringResource(R.string.label_locked)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (equipped) MaterialTheme.colorScheme.primary else IcingFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Swatch(tint: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(tint),
    )
}

/** A trail reads as a fading streak rather than a dot, so a locked "No Trail" is not mistaken for one. */
@Composable
private fun TrailSwatch(tint: Color) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                Brush.horizontalGradient(listOf(Color.Transparent, tint)),
            ),
    )
}

@Composable
private fun BallSkin.label(): String = stringResource(
    when (this) {
        BallSkin.DEFAULT -> R.string.skin_name_default
        BallSkin.GREEN -> R.string.skin_name_green
        BallSkin.PURPLE -> R.string.skin_name_purple
        BallSkin.BLUE -> R.string.skin_name_blue
        BallSkin.GOLD -> R.string.skin_name_gold
    },
)

/** Display tint only — the real ball is a sprite (`BallSkin.spriteName`) drawn by A1's renderer. */
private fun BallSkin.tint(): Color = when (this) {
    BallSkin.DEFAULT -> BallDefault
    BallSkin.GREEN -> CandyGreen
    BallSkin.PURPLE -> CandyPurple
    BallSkin.BLUE -> CandyBlue
    BallSkin.GOLD -> CandyGold
}

@Composable
private fun TrailType.label(): String = stringResource(
    when (this) {
        TrailType.NONE -> R.string.trail_name_none
        TrailType.GREEN -> R.string.trail_name_green
        TrailType.PURPLE -> R.string.trail_name_purple
        TrailType.PINK -> R.string.trail_name_pink
        TrailType.BLUE -> R.string.trail_name_blue
    },
)

private fun TrailType.tint(): Color = when (this) {
    TrailType.NONE -> IcingFaint
    TrailType.GREEN -> CandyGreen
    TrailType.PURPLE -> CandyPurple
    TrailType.PINK -> CandyRose
    TrailType.BLUE -> CandyBlue
}

// ---------------------------------------------------------------------------
// Design documentation — see the note in JarCanvas.kt on why these are previews and not tests.
// ---------------------------------------------------------------------------

@Preview(name = "Selector — fresh install", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun CosmeticSelectorFreshPreview() {
    CandyTriangleTheme {
        CosmeticSelector(
            unlockedSkins = setOf(BallSkin.DEFAULT),
            equippedSkin = BallSkin.DEFAULT,
            onEquipSkin = {},
            unlockedTrails = setOf(TrailType.NONE),
            equippedTrail = TrailType.NONE,
            onEquipTrail = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Selector — part unlocked", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun CosmeticSelectorUnlockedPreview() {
    CandyTriangleTheme {
        CosmeticSelector(
            // Pink's tier-1 reward is GOLD, not a pink skin — PRD §7, deliberate.
            unlockedSkins = setOf(BallSkin.DEFAULT, BallSkin.GREEN, BallSkin.GOLD),
            equippedSkin = BallSkin.GREEN,
            onEquipSkin = {},
            unlockedTrails = setOf(TrailType.NONE, TrailType.GREEN),
            equippedTrail = TrailType.GREEN,
            onEquipTrail = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
