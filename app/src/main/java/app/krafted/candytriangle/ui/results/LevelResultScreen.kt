package app.krafted.candytriangle.ui.results

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.ui.components.CandyBackdrop
import app.krafted.candytriangle.ui.components.CandyPrimaryButton
import app.krafted.candytriangle.ui.components.CandySecondaryButton
import app.krafted.candytriangle.ui.components.candyDialogEntrance
import app.krafted.candytriangle.ui.game.LevelOutcome
import app.krafted.candytriangle.ui.theme.CandyCyan
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyGreen
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyPurple
import app.krafted.candytriangle.ui.theme.CandyRose
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.StatusError
import kotlinx.coroutines.delay

private val ResultCardShape = RoundedCornerShape(28.dp)

/** Win and fail destinations fed only by the already-persisted immutable attempt outcome. */
@Composable
fun LevelResultScreen(
    outcome: LevelOutcome,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseScore = (outcome.score - outcome.sugarRushBonus).coerceAtLeast(0)
    var displayedScore by remember(outcome) { mutableIntStateOf(if (outcome.won) baseScore else outcome.score) }
    var crownsRevealed by remember(outcome) { mutableIntStateOf(0) }
    var showRush by remember(outcome) { mutableStateOf(false) }

    LaunchedEffect(outcome) {
        if (!outcome.won) return@LaunchedEffect
        delay(220)
        showRush = true
        val delta = outcome.score - baseScore
        if (delta > 0) {
            for (step in 1..36) {
                displayedScore = baseScore + delta * step / 36
                delay(18)
            }
        }
        for (crown in 1..outcome.crowns) {
            delay(180)
            crownsRevealed = crown
        }
    }

    CandyBackdrop(imageRes = R.drawable.world_3, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = levelLabel(outcome.levelId),
                style = MaterialTheme.typography.labelLarge,
                color = CandyCyan,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .candyDialogEntrance()
                    .fillMaxWidth()
                    .background(NightSurface.copy(alpha = 0.94f), ResultCardShape)
                    .border(
                        width = 2.dp,
                        color = if (outcome.won) CandyPink else StatusError,
                        shape = ResultCardShape,
                    )
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        if (outcome.won) R.string.result_complete_title else R.string.result_failed_title,
                    ),
                    style = MaterialTheme.typography.headlineLarge,
                    color = IcingWhite,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(
                        if (outcome.won) R.string.result_complete_message else R.string.result_failed_message,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IcingDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(20.dp))
                if (outcome.won) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) { index -> ResultCrown(earned = index < crownsRevealed) }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Text(
                    text = stringResource(R.string.label_score).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = IcingDim,
                )
                Text(
                    text = displayedScore.toString(),
                    style = MaterialTheme.typography.displayMedium,
                    color = CandyGold,
                )
                AnimatedVisibility(
                    visible = outcome.won && showRush,
                    enter = fadeIn(tween(240)) + slideInVertically(tween(320)) { it / 2 },
                ) {
                    Text(
                        text = stringResource(
                            R.string.result_sugar_rush,
                            outcome.ballsRemaining,
                            outcome.sugarRushBonus,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = CandyPink,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.result_banked_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = IcingWhite,
                )
                if (outcome.collected.isEmpty()) {
                    Text(
                        text = stringResource(R.string.result_no_candies),
                        style = MaterialTheme.typography.bodyMedium,
                        color = IcingDim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CandyColor.entries.forEach { color ->
                            val count = outcome.collected[color] ?: return@forEach
                            CandyBankChip(color, count)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                CandyPrimaryButton(
                    text = stringResource(if (outcome.won) R.string.action_continue else R.string.action_retry),
                    onClick = if (outcome.won) onContinue else onRetry,
                )
                Spacer(Modifier.height(10.dp))
                if (outcome.won) {
                    CandySecondaryButton(text = stringResource(R.string.result_replay), onClick = onRetry)
                    Spacer(Modifier.height(8.dp))
                }
                CandySecondaryButton(text = stringResource(R.string.action_home), onClick = onHome)
            }
        }
    }
}

@Composable
private fun ResultCrown(earned: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (earned) 1f else 0.72f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "Result crown scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (earned) 1f else 0.42f,
        animationSpec = tween(180),
        label = "Result crown alpha",
    )
    Image(
        painter = painterResource(if (earned) R.drawable.coin else R.drawable.crown_empty),
        contentDescription = stringResource(if (earned) R.string.cd_crown_earned else R.string.cd_crown_locked),
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                rotationZ = (1f - scale) * -18f
            },
    )
}

@Composable
private fun CandyBankChip(color: CandyColor, count: Int) {
    val tint = when (color) {
        CandyColor.GREEN -> CandyGreen
        CandyColor.PURPLE -> CandyPurple
        CandyColor.PINK -> CandyRose
        CandyColor.BLUE -> androidx.compose.ui.graphics.Color(0xFF33A0FF)
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(tint.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.65f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = "${color.configKey.lowercase()} candy, $count" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(color.drawableRes()),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelLarge,
            color = IcingWhite,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun levelLabel(levelId: Int): String = LevelIds.bonusCode(levelId)?.let {
    stringResource(R.string.game_sweet_room_label, it)
} ?: stringResource(R.string.game_level_label, levelId)

private fun CandyColor.drawableRes(): Int = when (this) {
    CandyColor.GREEN -> R.drawable.can_1
    CandyColor.PURPLE -> R.drawable.can_2
    CandyColor.PINK -> R.drawable.can_3
    CandyColor.BLUE -> R.drawable.can_4
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun ResultScreenPreview() {
    CandyTriangleTheme {
        LevelResultScreen(
            outcome = LevelOutcome(
                levelId = 7,
                won = true,
                crowns = 2,
                score = 8_450,
                ballsRemaining = 4,
                collected = mapOf(CandyColor.GREEN to 12, CandyColor.PINK to 8),
                sugarRushBonus = 2_000,
            ),
            onContinue = {},
            onRetry = {},
            onHome = {},
        )
    }
}
