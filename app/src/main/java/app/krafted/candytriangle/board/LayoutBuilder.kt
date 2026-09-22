package app.krafted.candytriangle.board

import app.krafted.candytriangle.engine.ColliderKind
import app.krafted.candytriangle.engine.Contact
import app.krafted.candytriangle.engine.Peg
import app.krafted.candytriangle.engine.PegMotion
import app.krafted.candytriangle.engine.PhysicsParams
import app.krafted.candytriangle.engine.WallSegment
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LayoutPattern
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.RowCol

/**
 * Builds the board's Peg layout from a LevelDef (§3.4, §8 `LayoutBuilder.kt`).
 */
object LayoutBuilder {

    /**
     * Builds the lattice of pegs and gem bodies for a level.
     * Enforces the §3.3 clearance rules to prevent balls getting stuck.
     */
    fun buildPegs(
        level: LevelDef,
        config: GameConfig,
        params: PhysicsParams,
    ): List<Peg> {
        val d = level.layout.spacing
        val rowHeight = d * config.board.lattice.rowSpacingFactor

        // Top of the lattice zone
        val startY = config.board.latticeBand.yTop // usually 150
        val endY = config.board.latticeBand.yBottom // usually 1170
        val centerX = config.board.centerAxisX

        val walls = WallSegment.boardWalls(config.board, params.restitutionWall)
        
        // Build the set of all valid RowCol coordinates
        val activeNodes = mutableSetOf<RowCol>()
        
        // Calculate max possible rows that fit
        val maxRows = Math.ceil(((endY - startY) / rowHeight).toDouble()).toInt()
        
        val clustersNodes = level.layout.clusters.flatMap { it.resolveNodes() }.toSet()
        val holesNodes = level.layout.holes.toSet()

        for (r in 0..maxRows) {
            val y = startY + r * rowHeight
            if (y > endY) continue

            // Determine if row is active based on pattern
            val rowActive = when (level.layout.pattern) {
                LayoutPattern.FULL -> true
                LayoutPattern.SPARSE -> r % 2 == 0
                LayoutPattern.CLUSTERS -> false // Only cluster nodes active
            }

            for (c in 0..r) {
                val rc = RowCol(r, c)
                
                var isActive = false
                if (level.layout.pattern == LayoutPattern.CLUSTERS) {
                    if (rc in clustersNodes) isActive = true
                } else {
                    if (rowActive && rc !in holesNodes) isActive = true
                }
                
                if (isActive) {
                    activeNodes.add(rc)
                }
            }
        }

        val movingRowsMap = if (config.worlds.firstOrNull { it.index == level.world }?.movingPegRows != false) {
            level.layout.movingRows.associateBy { it.row }
        } else emptyMap()

        val gemMap = level.gems.associateBy { RowCol(it.row, it.col) }
        val clearanceConfig = config.clearance
        val contact = Contact()

        // Pass 1: generate all pegs
        val preliminaryPegs = mutableListOf<TempPeg>()
        var nextId = 0
        
        for (rc in activeNodes) {
            val r = rc.row
            val c = rc.col
            val x = centerX + (c - r / 2.0f) * d
            val y = startY + r * rowHeight
            
            val movingDef = movingRowsMap[r]
            val amplitude = movingDef?.amplitude ?: 0f
            val absAmp = kotlin.math.abs(amplitude)
            
            // Check wall clearance (Rule: Ball between Peg & Wall)
            var tooCloseToWall = false
            val minRadius = clearanceConfig.minPegEdgeToWall + params.pegRadius
            for (wall in walls) {
                // Check resting position and both extremes of the swing
                if (wall.contact(x, y, minRadius, contact) ||
                    (absAmp > 0f && (wall.contact(x - absAmp, y, minRadius, contact) || wall.contact(x + absAmp, y, minRadius, contact)))
                ) {
                    tooCloseToWall = true
                    break
                }
            }
            if (tooCloseToWall) continue
            
            val isGem = gemMap.containsKey(rc)
            preliminaryPegs.add(TempPeg(rc, x, y, isGem))
        }

        // Pass 2: gem clearances (Rule: Ball between Gem & Peg)
        if (d < clearanceConfig.minSpacingBallBetweenGemAndPeg) {
            val gems = preliminaryPegs.filter { it.isGem }
            val pegsToRemove = mutableSetOf<RowCol>()
            
            // Adjacent in an equilateral lattice: distance <= d + epsilon
            val distThresholdSq = (d + 2f) * (d + 2f) 
            
            for (gem in gems) {
                for (peg in preliminaryPegs) {
                    if (peg.isGem) continue
                    val dx = peg.x - gem.x
                    val dy = peg.y - gem.y
                    if (dx * dx + dy * dy <= distThresholdSq) {
                        pegsToRemove.add(peg.rc)
                    }
                }
            }
            preliminaryPegs.removeIf { it.rc in pegsToRemove }
        }

        // Final output
        val finalPegs = mutableListOf<Peg>()
        for (tp in preliminaryPegs) {
            val movingDef = movingRowsMap[tp.rc.row]
            val motion = if (movingDef != null) {
                PegMotion(tp.x, movingDef.amplitude, movingDef.periodSeconds)
            } else null

            val kind = if (tp.isGem) ColliderKind.GEM else ColliderKind.PEG
            val radius = if (tp.isGem) params.gemRadius else params.pegRadius
            val restitution = if (tp.isGem) params.restitutionGem else params.restitutionPeg
            
            finalPegs.add(
                Peg(
                    id = nextId++,
                    x = tp.x,
                    y = tp.y,
                    radius = radius,
                    restitution = restitution,
                    kind = kind,
                    motion = motion
                )
            )
        }

        return finalPegs
    }

    private data class TempPeg(val rc: RowCol, val x: Float, val y: Float, val isGem: Boolean)
}
