package app.krafted.candytriangle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * TODO(D4 — Screens, Popups & Game Flow): switch [CandyFontFamily] to Fredoka.
 *
 * PRD §8 specifies Fredoka as the game face, but no font file ships in the
 * `plinko_dev` asset pack and the app declares no network permission, so the
 * .ttf cannot be fetched at build time or at runtime. Until someone drops the
 * files into `res/font/` (e.g. `fredoka_regular.ttf`, `fredoka_semibold.ttf`,
 * `fredoka_bold.ttf`) this stays on the platform default face.
 *
 * When the files land, this is a one-line change — every style below already
 * routes through [CandyFontFamily]:
 *
 *     val CandyFontFamily = FontFamily(
 *         Font(R.font.fredoka_regular, FontWeight.Normal),
 *         Font(R.font.fredoka_semibold, FontWeight.SemiBold),
 *         Font(R.font.fredoka_bold, FontWeight.Bold),
 *     )
 */
val CandyFontFamily: FontFamily = FontFamily.Default

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = CandyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
