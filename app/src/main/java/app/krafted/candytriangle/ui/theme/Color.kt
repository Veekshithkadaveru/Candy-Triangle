package app.krafted.candytriangle.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Candy Triangle palette.
 *
 * Mirrors `res/values/colors.xml`. Keep the two in sync: XML is used by the
 * window theme and adaptive icon, these are used by Compose and the Canvas
 * renderer. Board-entity tints that a designer may want to retune without a
 * rebuild also live in `assets/config.json`
 * (`worlds[].pegTint`, `candies.colors[].hex`).
 */

// ---------------------------------------------------------------- Brand neon
val CandyPink = Color(0xFFFF4FC8)
val CandyCyan = Color(0xFF3FE3FF)
val CandyViolet = Color(0xFFA56BFF)
val CandyGold = Color(0xFFFFC23F)

// ------------------------------------------------- World peg tints (PRD §6.1)
val World1Pink = CandyPink
val World2Cyan = CandyCyan
val World3Violet = CandyViolet
val World4Gold = CandyGold

/** Peg tints in world order; index with `world - 1` for worlds 1..4. */
val WorldPegTints = listOf(World1Pink, World2Cyan, World3Violet, World4Gold)

// ------------------------------------------------ Candy colours (PRD §4.1)
val CandyGreen = Color(0xFF3DE84B)
val CandyPurple = Color(0xFFB23CF0)
val CandyRose = Color(0xFFFF4FC8)
val CandyBlue = Color(0xFF33A0FF)

// -------------------------------------------------- Gem colours (PRD §4.2)
val GemSweet = Color(0xFFB347E8)
val GemBlast = Color(0xFFFFA52B)
val GemLine = Color(0xFF14D8EB)
val GemSplit = Color(0xFFFF4030)
val GemExtraBall = Color(0xFF2BE05C)
val GemMagnet = Color(0xFF2E6BFF)
val GemSugarStorm = Color(0xFFFF1F6B)

// ------------------------------------------------------------ Board pieces
val BallDefault = Color(0xFFFF2EAF)
val CrownGold = Color(0xFFFFB01F)
val CrownEmpty = Color(0xFF4A4358)
val CupRimGold = Color(0xFFFFC23F)

/** 45% dark scrim drawn over world backdrops on canvas (PRD §9.1). */
val BackdropScrim = Color(0x73000000)

// ------------------------------------------------------------- Dark surfaces
val NightVoid = Color(0xFF0B0518)
val NightSurface = Color(0xFF1A0B2E)
val NightSurfaceHigh = Color(0xFF2A1148)
val NightOutline = Color(0xFF4A2A6E)
val NightOutlineDim = Color(0xFF32215A)
val DialogScrim = Color(0xB3000000)

// -------------------------------------------------------------------- Text
val IcingWhite = Color(0xFFF5ECFF)
val IcingDim = Color(0xFFC3AEDC)
val IcingFaint = Color(0xFF7A6A93)

// ------------------------------------------------------------------ Status
val StatusSuccess = Color(0xFF3DE84B)
val StatusWarning = Color(0xFFFFC23F)
val StatusError = Color(0xFFFF6B6B)
val LockedGate = Color(0xFF5A4A70)

// --------------------------------------------- Material container roles
val OnCandyPink = Color(0xFF2A0016)
val CandyPinkContainer = Color(0xFF6B0046)
val OnCandyPinkContainer = Color(0xFFFFD6EF)

val OnCandyCyan = Color(0xFF00222B)
val CandyCyanContainer = Color(0xFF00485C)
val OnCandyCyanContainer = Color(0xFFCFF6FF)

val OnCandyGold = Color(0xFF2E1C00)
val CandyGoldContainer = Color(0xFF5C3A00)
val OnCandyGoldContainer = Color(0xFFFFE6B0)

val OnStatusError = Color(0xFF3A0000)
val StatusErrorContainer = Color(0xFF7A1B1B)
val OnStatusErrorContainer = Color(0xFFFFDAD6)
