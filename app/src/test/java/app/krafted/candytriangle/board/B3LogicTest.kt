package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.ChainConfig
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemEffect
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.RowCol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B3LogicTest {

    @Test
    fun testCandySensorContact() {
        val rc = RowCol(0, 0)
        val candy = Candy(1, CandyColor.GREEN, 500f, 150f, rc)
        
        // Exact center overlaps
        assertTrue(candy.contact(500f, 150f, 16f, 22f))
        
        // Just within reach (16 + 22 = 38). Distance is 30.
        assertTrue(candy.contact(530f, 150f, 16f, 22f))
        
        // Out of reach. Distance is 40.
        assertFalse(candy.contact(540f, 150f, 16f, 22f))
        
        // Deactivated candy shouldn't contact
        candy.active = false
        assertFalse(candy.contact(500f, 150f, 16f, 22f))
    }

    @Test
    fun testChainTracker() {
        val config = ChainConfig(
            sugarPopChain = 3,
            extraBallChain = 5,
            extraBallMaxPerLaunchedBall = 1,
            chainResetValueOnColourChange = 1,
            onlyDirectContactExtendsChain = true,
            effectPoppedCandiesExtendChain = false
        )
        val tracker = ChainTracker(config)
        val rc = RowCol(0, 0)
        val candyGreen1 = Candy(1, CandyColor.GREEN, 0f, 0f, rc)
        val candyGreen2 = Candy(2, CandyColor.GREEN, 0f, 0f, rc)
        val candyPurple = Candy(3, CandyColor.PURPLE, 0f, 0f, rc)

        // Hit green direct
        var res = tracker.onCandyPopped(candyGreen1, direct = true)
        assertEquals(1, res.chainLength)
        assertEquals(CandyColor.GREEN, tracker.currentColor)
        assertFalse(res.triggerSugarPop)

        // Hit green again via effect (should score but not extend)
        res = tracker.onCandyPopped(candyGreen2, direct = false)
        assertEquals(1, res.chainLength) // Still 1

        // Hit green direct 2 more times (chain = 3, Sugar Pop)
        tracker.onCandyPopped(candyGreen2, direct = true)
        res = tracker.onCandyPopped(candyGreen2, direct = true)
        assertEquals(3, res.chainLength)
        assertTrue(res.triggerSugarPop)
        assertFalse(res.triggerExtraBall)

        // Hit green direct 2 more times (chain = 5, Extra Ball)
        tracker.onCandyPopped(candyGreen2, direct = true)
        res = tracker.onCandyPopped(candyGreen2, direct = true)
        assertEquals(5, res.chainLength)
        assertTrue(res.triggerExtraBall)
        
        // Hit purple (changes color, resets to 1)
        res = tracker.onCandyPopped(candyPurple, direct = true)
        assertEquals(1, res.chainLength)
        assertEquals(CandyColor.PURPLE, tracker.currentColor)
    }

    @Test
    fun testGemEffectBlast() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        
        val candies = listOf(
            Candy(1, CandyColor.GREEN, 10f, 10f, RowCol(0,0)), // inside radius
            Candy(2, CandyColor.PURPLE, 20f, 20f, RowCol(1,0)), // inside radius
            Candy(3, CandyColor.BLUE, 300f, 300f, RowCol(5,5)) // outside radius
        )
        
        val peg = Peg(1, 0f, 0f, 30f, 0.65f, ColliderKind.GEM)
        val gem = Gem(GemType.BLAST, peg, RowCol(0,0))
        
        val state = BoardState(candies, listOf(gem), params)
        
        val effect = GemEffect.PopRadius(2.5f)
        val result = GemEffectHandlers.applyEffect(effect, gem, state, 80f)
        
        assertEquals(2, result.poppedCandies.size)
        assertTrue(result.poppedCandies.any { it.id == 1 })
        assertTrue(result.poppedCandies.any { it.id == 2 })
        assertFalse(result.poppedCandies.any { it.id == 3 })
    }
    
    @Test
    fun testBoardStateDropMultiplier() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        
        val peg1 = Peg(1, 0f, 0f, 30f, 0.65f, ColliderKind.GEM)
        val gemBlast = Gem(GemType.BLAST, peg1, RowCol(0,0)) // multiplier 10
        
        val peg2 = Peg(2, 0f, 0f, 30f, 0.65f, ColliderKind.GEM)
        val gemMagnet = Gem(GemType.MAGNET, peg2, RowCol(0,1)) // multiplier 35
        
        val state = BoardState(emptyList(), listOf(gemBlast, gemMagnet), params)
        
        assertEquals(1, state.dropMultiplier)
        
        state.registerGemBreak(gemBlast)
        assertEquals(10, state.dropMultiplier)
        assertFalse(gemBlast.active)
        
        state.registerGemBreak(gemMagnet)
        assertEquals(35, state.dropMultiplier)
        assertFalse(gemMagnet.active)
        
        state.resetForDrop()
        assertEquals(1, state.dropMultiplier)
    }
}
