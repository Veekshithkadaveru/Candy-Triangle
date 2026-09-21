package app.krafted.candytriangle.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The one and only Candy Triangle colour scheme.
 *
 * Deliberately dark-only and *not* wallpaper-derived: Material You dynamic
 * colour would let the device wallpaper repaint a branded neon game, so it is
 * disabled outright. There is no light scheme — the board art, the peg glow and
 * the 45% backdrop scrim all assume a near-black ground.
 */
private val CandyDarkColorScheme = darkColorScheme(
    primary = CandyPink,
    onPrimary = OnCandyPink,
    primaryContainer = CandyPinkContainer,
    onPrimaryContainer = OnCandyPinkContainer,

    secondary = CandyCyan,
    onSecondary = OnCandyCyan,
    secondaryContainer = CandyCyanContainer,
    onSecondaryContainer = OnCandyCyanContainer,

    tertiary = CandyGold,
    onTertiary = OnCandyGold,
    tertiaryContainer = CandyGoldContainer,
    onTertiaryContainer = OnCandyGoldContainer,

    background = NightVoid,
    onBackground = IcingWhite,

    surface = NightSurface,
    onSurface = IcingWhite,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = IcingDim,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    inverseSurface = IcingWhite,
    inverseOnSurface = NightVoid,

    outline = NightOutline,
    outlineVariant = NightOutlineDim,
    scrim = DialogScrim,

    error = StatusError,
    onError = OnStatusError,
    errorContainer = StatusErrorContainer,
    onErrorContainer = OnStatusErrorContainer,
)

/**
 * Wraps [content] in the Candy Triangle Material 3 theme.
 *
 * Signature is intentionally a single `content` lambda — the scheme is fixed, so
 * there is no `darkTheme` or `dynamicColor` knob to pass.
 */
@Composable
fun CandyTriangleTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // The app is always dark, so the system bars always want light
            // (white) icons — regardless of the device's own light/dark setting.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = CandyDarkColorScheme,
        typography = Typography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
