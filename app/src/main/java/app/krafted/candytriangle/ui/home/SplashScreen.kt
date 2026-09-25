package app.krafted.candytriangle.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.ui.components.CandyBackdrop
import app.krafted.candytriangle.ui.theme.CandyCyan
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightVoid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A staged, deterministic brand reveal while process-scoped stores warm up lazily. */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val emblemAlpha = remember { Animatable(0f) }
    val emblemScale = remember { Animatable(0.62f) }
    val emblemRotation = remember { Animatable(-8f) }
    val backgroundScale = remember { Animatable(1.10f) }
    var showCopy by remember { mutableStateOf(false) }
    var showLoading by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    val contentAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(220),
        label = "Splash exit alpha",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (leaving) 1.04f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "Splash exit scale",
    )

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                backgroundScale.animateTo(1f, tween(1_650, easing = FastOutSlowInEasing))
            }
            launch { emblemAlpha.animateTo(1f, tween(240)) }
            launch {
                emblemScale.animateTo(
                    1f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
            launch { emblemRotation.animateTo(0f, tween(620, easing = FastOutSlowInEasing)) }

            delay(280)
            showCopy = true
            delay(260)
            showLoading = true
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            delay(100)
            leaving = true
            delay(220)
        }
        onFinished()
    }

    CandyBackdrop(
        imageRes = R.drawable.splash,
        modifier = modifier,
        scrim = Color.Black.copy(alpha = 0.40f),
        backgroundScale = backgroundScale.value,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 52.dp)
                .graphicsLayer {
                    alpha = contentAlpha
                    scaleX = contentScale
                    scaleY = contentScale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))
            SplashEmblem(
                modifier = Modifier.graphicsLayer {
                    alpha = emblemAlpha.value
                    scaleX = emblemScale.value
                    scaleY = emblemScale.value
                    rotationZ = emblemRotation.value
                },
            )
            AnimatedVisibility(
                visible = showCopy,
                enter = fadeIn(tween(420)) +
                    slideInVertically(tween(460, easing = FastOutSlowInEasing)) { it / 3 } +
                    scaleIn(tween(460), initialScale = 0.94f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name).uppercase(),
                        style = MaterialTheme.typography.displayMedium,
                        color = IcingWhite,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.splash_tagline),
                        style = MaterialTheme.typography.labelLarge,
                        color = CandyCyan,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = showLoading,
                enter = fadeIn(tween(260)) + slideInVertically(tween(360)) { it / 2 },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 300.dp)
                            .height(8.dp)
                            .clip(CircleShape)
                            .semantics {
                                progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f)
                            },
                        color = CandyPink,
                        trackColor = CandyCyan.copy(alpha = 0.22f),
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        text = stringResource(R.string.splash_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = IcingDim,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SplashEmblem(modifier: Modifier = Modifier) {
    val orbit = rememberInfiniteTransition(label = "Splash emblem motion")
    val rotation by orbit.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing)),
        label = "Orbit rotation",
    )
    val pulse by orbit.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Emblem glow pulse",
    )

    Box(modifier = modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = pulse; scaleY = pulse }) {
            val radius = size.minDimension * 0.43f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to CandyPink.copy(alpha = 0.22f),
                    0.62f to CandyCyan.copy(alpha = 0.08f),
                    1f to Color.Transparent,
                ),
                radius = size.minDimension * 0.50f,
            )
            drawCircle(
                color = CandyCyan.copy(alpha = 0.28f),
                radius = radius,
                style = Stroke(width = 1.dp.toPx()),
            )
            repeat(3) { index ->
                val angle = (rotation + index * 120f) * PI.toFloat() / 180f
                drawCircle(
                    color = if (index == 0) CandyPink else CandyCyan,
                    radius = if (index == 0) 4.dp.toPx() else 3.dp.toPx(),
                    center = Offset(
                        x = center.x + cos(angle) * radius,
                        y = center.y + sin(angle) * radius,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(210.dp)
                .background(NightVoid.copy(alpha = 0.18f), CircleShape),
        )
        Image(
            painter = painterResource(R.drawable.triangle),
            contentDescription = stringResource(R.string.cd_app_emblem),
            modifier = Modifier.size(206.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SplashScreenPreview() {
    CandyTriangleTheme { SplashScreen(onFinished = {}) }
}
