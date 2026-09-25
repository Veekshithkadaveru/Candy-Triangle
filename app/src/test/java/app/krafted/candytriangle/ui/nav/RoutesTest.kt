package app.krafted.candytriangle.ui.nav

import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.ui.game.LevelOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins every result route segment so navigation can never silently drop a banked colour. */
class RoutesTest {

    @Test
    fun resultRouteCarriesTheCompleteImmutableOutcomeInColourOrder() {
        val route = Routes.result(
            LevelOutcome(
                levelId = 104,
                won = true,
                crowns = 3,
                score = 12_345,
                ballsRemaining = 7,
                collected = mapOf(
                    CandyColor.BLUE to 4,
                    CandyColor.GREEN to 1,
                    CandyColor.PINK to 3,
                    CandyColor.PURPLE to 2,
                ),
                sugarRushBonus = 3_500,
            ),
        )

        assertEquals("result/104/true/3/12345/7/3500/1/2/3/4", route)
    }

    @Test
    fun absentCandyColoursAreEncodedAsZero() {
        val route = Routes.result(
            LevelOutcome(2, false, 0, 90, 0, emptyMap(), sugarRushBonus = 0),
        )

        assertEquals("result/2/false/0/90/0/0/0/0/0/0", route)
    }
}
