package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.PhysicsWorld
import app.krafted.candytriangle.engine.WallSegment
import app.krafted.candytriangle.level.BoardConfig
import app.krafted.candytriangle.level.CandyPlacementDef
import app.krafted.candytriangle.level.CupDef
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GemPlacementDef
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LayoutDef
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class B2LogicTest {

    @Test
    fun testCandyCupMotion() {
        val cup = CandyCup(width = 180f, speed = 200f)
        assertEquals(500f, cup.x)
        
        // Move right by 1s -> x should be 700
        cup.updateMotion(1f)
        assertEquals(700f, cup.x)
        
        // Move right by 2s -> 200 * 2 = 400. 700 + 400 = 1100.
        // MaxX is 1000 - 90 = 910. 
        // x goes to 1100. Bounce: 910 - (1100 - 910) = 910 - 190 = 720. Direction becomes -1.
        cup.updateMotion(2f)
        assertEquals(720f, cup.x)
        assertEquals(-1, cup.direction)
    }

    @Test
    fun testLayoutBuilder() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        
        // Create a simple level
        val level = LevelDef(
            id = 1,
            code = null,
            world = 1,
            isBonus = false,
            balls = 10,
            crowns = listOf(2, 4),
            layout = LayoutDef(
                spacing = 80f,
                pattern = LayoutPattern.FULL,
                holes = emptyList(),
                clusters = emptyList(),
                movingRows = emptyList(),
            ),
            candies = CandyPlacementDef(1, 0, emptyMap(), emptyList()),
            gems = listOf(
                GemPlacementDef(GemType.BLAST, row = 5, col = 2)
            ),
            cup = CupDef(200f),
            objectives = emptyList(),
        )

        val pegs = LayoutBuilder.buildPegs(level, config, params)
        assertTrue("Pegs should be generated", pegs.isNotEmpty())
        
        // Find gem
        val gems = pegs.filter { it.kind == ColliderKind.GEM }
        assertEquals("There should be exactly one gem", 1, gems.size)
        
        // Check that none are too close to walls
        val walls = WallSegment.boardWalls(config.board, params.restitutionWall)
        val contact = app.krafted.candytriangle.engine.Contact()
        var violated = false
        for (p in pegs) {
            for (w in walls) {
                if (w.contact(p.x, p.y, config.clearance.minPegEdgeToWall + params.pegRadius, contact)) {
                    violated = true
                }
            }
        }
        assertTrue("No pegs should be closer than 36u to wall", !violated)
    }
    
    @Test
    fun testLauncherPrediction() {
        val config = GameConfig.DEFAULTS
        val params = PhysicsParams.from(config)
        val launcher = Launcher(params)
        
        // Aim straight down
        launcher.aimRadians = 0f
        
        // Create empty world
        val world = PhysicsWorld.create(config, emptyList())
        val path = launcher.predictTrajectory(world)
        
        // Should eventually go out of bounds
        assertTrue("Path should be recorded", path.isNotEmpty())
        val lastPoint = path.last()
        assertTrue("Last point should be off board", lastPoint.y >= params.exitY)
    }
}
