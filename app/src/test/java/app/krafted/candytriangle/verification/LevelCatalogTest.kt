package app.krafted.candytriangle.verification

import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.ObjectiveType
import app.krafted.candytriangle.verification.RealLevels.arrSize
import app.krafted.candytriangle.verification.RealLevels.floatOrNull
import app.krafted.candytriangle.verification.RealLevels.intOrNull
import app.krafted.candytriangle.verification.RealLevels.obj
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * The shipped catalogue's shape against §6 (worlds, ramps, gem introductions, objectives, crowns),
 * and a raw-JSON cross-check that the tolerant mapper (A2 note 7) silently dropped nothing.
 */
class LevelCatalogTest {

    private val levels get() = RealLevels.levels
    private val config get() = RealLevels.config

    @Test
    fun catalogueHasExactlyTheFortyMainLevelsAndFourSweetRooms() {
        val expected = (LevelIds.MAIN_FIRST..LevelIds.MAIN_LAST).toList() +
            (LevelIds.BONUS_FIRST..LevelIds.BONUS_LAST).toList()
        assertEquals("level ids", expected, levels.map { it.id })
    }

    @Test
    fun rawEntriesAreAllMappedWithNothingDropped() {
        val raw = RealLevels.raw
        val failures = LevelFailures("raw levels.json vs mapped catalogue")
        if (raw.size != levels.size) {
            failures.add("catalogue", "raw has ${raw.size} level entries, mapped ${levels.size}")
        }
        val rawIds = raw.map { it.obj().intOrNull("id") }
        if (rawIds.toSet().size != rawIds.size) {
            failures.add("catalogue", "raw ids are not unique: $rawIds")
        }
        for (entry in raw) {
            val o = entry.obj()
            val id = o.intOrNull("id")
            if (o == null || id == null) {
                failures.add("catalogue", "raw entry without an integer id: $entry")
                continue
            }
            val level = RealLevels.catalog.level(id)
            if (level == null) {
                failures.add("raw id $id", "not in the mapped catalogue")
                continue
            }
            val layout = o.get("layout").obj()
            val candies = o.get("candies").obj()
            fun same(what: String, rawCount: Int, mapped: Int) =
                failures.check(level, rawCount == mapped) { "$what: raw $rawCount, mapped $mapped" }
            same("gems", o.arrSize("gems"), level.gems.size)
            same("objectives", o.arrSize("objectives"), level.objectives.size)
            same("fixed candies", candies.arrSize("fixed"), level.candies.fixed.size)
            same("holes", layout.arrSize("holes"), level.layout.holes.size)
            same("clusters", layout.arrSize("clusters"), level.layout.clusters.size)
            same("movingRows", layout.arrSize("movingRows"), level.layout.movingRows.size)

            // Scalars the mapper would otherwise clamp or default without a trace.
            val spacing = layout.floatOrNull("spacing")
            failures.check(level, spacing != null && spacing == level.layout.spacing) {
                "layout.spacing raw $spacing, mapped ${level.layout.spacing} (missing or clamped to 66..90)"
            }
            failures.check(level, o.intOrNull("balls") == level.balls) {
                "balls raw ${o.intOrNull("balls")}, mapped ${level.balls}"
            }
            failures.check(level, candies.intOrNull("count") == level.candies.count) {
                "candies.count raw ${candies.intOrNull("count")}, mapped ${level.candies.count}"
            }
            val crowns = o.get("crowns")
            failures.check(
                level,
                crowns != null && crowns.isJsonArray && crowns.asJsonArray.size() == 2 &&
                    crowns.asJsonArray.all { it.isJsonPrimitive } &&
                    crowns.asJsonArray.map { it.asInt } == level.crowns,
            ) { "crowns raw $crowns, mapped ${level.crowns} (not a 2-int array)" }
            val cupSpeed = o.get("cup").obj().floatOrNull("speed")
            failures.check(level, cupSpeed != null && cupSpeed == level.cup.speed) {
                "cup.speed raw $cupSpeed, mapped ${level.cup.speed}"
            }
            val rawWeights = candies?.get("weights").obj()
            // Omitted weights legitimately mean uniform; authored ones must all survive mapping.
            if (rawWeights != null) {
                failures.check(level, rawWeights.size() == level.candies.weights.size) {
                    "candies.weights raw ${rawWeights.keySet()}, mapped ${level.candies.weights.keys} " +
                        "(an unknown or negative key was dropped, or all-zero fell back to uniform)"
                }
            }
        }
        failures.assertNone()
    }

    @Test
    fun worldsCodesAndPerWorldRampsMatchSection6() {
        val failures = LevelFailures("§6.1 worlds and ramps")
        for (level in levels) {
            if (level.isBonus) {
                failures.check(level, level.code == LevelIds.bonusCode(level.id)) {
                    "code ${level.code}, expected ${LevelIds.bonusCode(level.id)}"
                }
                failures.check(level, level.world == 0) { "world ${level.world}, expected 0" }
                failures.check(level, level.balls == SWEET_ROOM_BALLS) {
                    "balls ${level.balls}, expected $SWEET_ROOM_BALLS"
                }
                failures.check(level, level.cup.speed in config.cup.speedMin..config.cup.speedMax) {
                    "cup speed ${level.cup.speed} outside ${config.cup.speedMin}..${config.cup.speedMax}"
                }
                continue
            }
            val world = (level.id - 1) / 10 + 1
            failures.check(level, level.world == world) { "world ${level.world}, expected $world" }
            val ramp = RAMPS.getValue(world)
            val t = ((level.id - 1) % 10) / 9.0
            val balls = ramp.ballsFrom + (ramp.ballsTo - ramp.ballsFrom) * t
            val spacing = ramp.spacingFrom + (ramp.spacingTo - ramp.spacingFrom) * t
            failures.check(level, abs(level.balls - balls) <= 1.0 + 1e-9) {
                "balls ${level.balls}, §6.1 ramp ${fmt(balls)} ± 1"
            }
            failures.check(level, abs(level.layout.spacing - spacing) <= 1.0 + 1e-6) {
                "spacing ${level.layout.spacing}, §6.1 ramp ${fmt(spacing)} ± 1"
            }
            failures.check(level, abs(level.cup.speed - ramp.cupSpeed) <= 1.0 + 1e-6) {
                "cup speed ${level.cup.speed}, §6.1 ${ramp.cupSpeed} ± 1"
            }
        }
        failures.assertNone()
    }

    @Test
    fun gemsAppearOnlyFromTheirIntroLevelAndEachIntroLevelTeachesItsGem() {
        val failures = LevelFailures("§4.2 gem introductions")
        for (level in levels) {
            for (gem in level.gems) {
                val intro = introLevel(gem.type)
                failures.check(level, level.id >= intro) {
                    "places ${gem.type} at (${gem.row}, ${gem.col}) before its intro level $intro"
                }
            }
            for (o in level.objectives) {
                val gem = o.gem ?: continue
                failures.check(level, level.id >= introLevel(gem)) {
                    "objective $o names $gem before its intro level ${introLevel(gem)}"
                }
            }
            val storms = level.gems.count { it.type == GemType.SUGAR_STORM }
            failures.check(level, storms <= 1) { "$storms SUGAR_STORM gems (max 1 per level)" }
        }
        for (type in GemType.entries) {
            val intro = introLevel(type)
            val level = RealLevels.catalog.level(intro)
            if (level == null) {
                failures.add("L$intro", "intro level of $type is missing")
                continue
            }
            failures.check(level, level.gems.any { it.type == type }) {
                "is $type's intro level but places no $type gem"
            }
            failures.check(
                level,
                level.objectives.any { it.type == ObjectiveType.COLLECT_GEM && it.gem == type },
            ) { "is $type's intro level but has no COLLECT_GEM objective for it: ${level.objectives}" }
        }
        failures.assertNone()
    }

    @Test
    fun movingRowsObjectiveCountsAndCrownThresholdsFollowTheRules() {
        val failures = LevelFailures("§6 moving rows / objectives / crowns")
        for (level in levels) {
            if (level.layout.movingRows.isNotEmpty()) {
                failures.check(level, !level.isBonus && level.world >= 3) {
                    "world ${level.world} has moving rows ${level.layout.movingRows.map { it.row }} " +
                        "(World 3+ only)"
                }
            }
            failures.check(level, level.objectives.isNotEmpty()) { "has no objectives" }
            if (!level.isBonus && level.world == 4) {
                failures.check(level, level.objectives.size == 2) {
                    "World 4 needs exactly 2 objectives, has ${level.objectives.size}"
                }
            }
            val (two, three) = level.crowns
            failures.check(level, 1 <= two && two < three && three <= level.balls - 1) {
                "crowns $two/$three break 1 <= two < three <= balls - 1 (balls ${level.balls})"
            }
        }
        failures.assertNone()
    }

    private fun introLevel(type: GemType): Int = config.gem(type)?.introLevel ?: type.introLevel

    private fun fmt(v: Double) = "%.2f".format(v)

    private class Ramp(
        val ballsFrom: Int,
        val ballsTo: Int,
        val spacingFrom: Double,
        val spacingTo: Double,
        val cupSpeed: Double,
    )

    private companion object {
        const val SWEET_ROOM_BALLS = 15

        /** §6.1, verbatim: balls, spacing d and cup speed per world. */
        val RAMPS = mapOf(
            1 to Ramp(12, 10, 90.0, 84.0, 200.0),
            2 to Ramp(11, 9, 84.0, 78.0, 260.0),
            3 to Ramp(10, 9, 78.0, 72.0, 320.0),
            4 to Ramp(10, 8, 72.0, 66.0, 380.0),
        )
    }
}
