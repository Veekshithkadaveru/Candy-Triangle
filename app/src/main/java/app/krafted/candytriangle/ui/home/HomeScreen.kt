package app.krafted.candytriangle.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.ui.components.CandyBackdrop
import app.krafted.candytriangle.ui.components.CandyPrimaryButton
import app.krafted.candytriangle.ui.components.CandySecondaryButton
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import kotlinx.coroutines.delay

/** The real start hub: every durable top-level destination is reachable without a dev route. */
@Composable
fun HomeScreen(
    onPlay: () -> Unit,
    onOpenJar: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEmblem by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showEmblem = true
        delay(100)
        showCopy = true
        delay(150)
        showActions = true
    }

    val ambientMotion = rememberInfiniteTransition(label = "Home ambient motion")
    val backgroundScale by ambientMotion.animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(8_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Home backdrop drift",
    )
    val emblemFloat by ambientMotion.animateFloat(
        initialValue = -4f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Home emblem float",
    )
    val density = LocalDensity.current

    CandyBackdrop(
        imageRes = R.drawable.world_2,
        modifier = modifier,
        backgroundScale = backgroundScale,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.35f))
            AnimatedVisibility(
                visible = showEmblem,
                enter = fadeIn(tween(340)) +
                    scaleIn(
                        animationSpec = tween(520, easing = FastOutSlowInEasing),
                        initialScale = 0.78f,
                    ),
            ) {
                Image(
                    painter = painterResource(R.drawable.triangle),
                    contentDescription = stringResource(R.string.cd_app_emblem),
                    modifier = Modifier
                        .size(230.dp)
                        .graphicsLayer {
                            translationY = with(density) { emblemFloat.dp.toPx() }
                        },
                )
            }
            AnimatedVisibility(
                visible = showCopy,
                enter = fadeIn(tween(360)) +
                    slideInVertically(tween(420, easing = FastOutSlowInEasing)) { it / 3 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        color = IcingWhite,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = IcingDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.weight(0.55f))
            AnimatedVisibility(
                visible = showActions,
                enter = fadeIn(tween(320)) +
                    slideInVertically(tween(460, easing = FastOutSlowInEasing)) { it / 2 },
            ) {
                Column {
                    CandyPrimaryButton(text = stringResource(R.string.action_play), onClick = onPlay)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CandySecondaryButton(
                            text = stringResource(R.string.label_candy_jar),
                            onClick = onOpenJar,
                            modifier = Modifier.weight(1f),
                            accent = CandyGold,
                        )
                        CandySecondaryButton(
                            text = stringResource(R.string.action_settings),
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun HomeScreenPreview() {
    CandyTriangleTheme { HomeScreen({}, {}, {}) }
}
