package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.DEFAULT_GATES
import app.krafted.candytriangle.level.DEFAULT_WORLDS
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GateDef
import app.krafted.candytriangle.level.JarConfig
import app.krafted.candytriangle.level.JarDef
import app.krafted.candytriangle.level.TrailType
import app.krafted.candytriangle.ui.jar.JarUiStateMapper
import app.krafted.candytriangle.verification.RealLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every §6.2 / §7 lock decision the map draws, on the JVM.
 *
 * `MapStateMapper` is a pure object precisely so this can exist: no composable runs in §11's
 * JVM-only suite, so if `LevelMapScreen` made a single lock decision itself, nothing would test it.
 */
class MapStateMapperTest {

    // ---------------------------------------------------------------- fresh install and the road

    @Test
    fun aFreshInstallOpensLevelOneAndNothingElse() {
        val state = map()

        assertEquals(listOf(1, 2, 3, 4), state.slices.map { it.world })
        assertEquals((1..40).toList(), state.allLevels.map { it.levelId })
        assertEquals(NodeStatus.AVAILABLE, state.node(1).status)
        assertTrue(state.node(1).isCurrent)
        for (id in 2..40) assertEquals("L$id", NodeStatus.LOCKED, state.node(id).status)
        assertEquals(1, state.currentLevelId)
        assertEquals(0, state.currentSliceIndex)

        assertNull("World 1 has no gate", state.slice(1)!!.gate)
        for (world in 2..4) assertFalse("World $world", state.gate(world).open)
        assertEquals(listOf(true, false, false, false), state.slices.map { it.unlocked })
        assertEquals(List(4) { NodeStatus.LOCKED }, state.slices.map { it.sweetRoom!!.status })

        assertEquals(0, state.totalCrowns)
        assertEquals(120, state.maxCrowns)
        assertEquals(List(4) { 0 }, state.slices.map { it.crownsEarned })
        assertTrue(state.loaded)
    }

    @Test
    fun clearingLevelOneOpensLevelTwoAndMovesTheMarker() {
        val state = map(progress(crowns = mapOf(1 to 1)))

        assertEquals(NodeStatus.CLEARED, state.node(1).status)
        assertEquals(1, state.node(1).crowns)
        assertFalse(state.node(1).isCurrent)
        assertEquals(NodeStatus.AVAILABLE, state.node(2).status)
        assertTrue(state.node(2).isCurrent)
        assertEquals(2, state.currentLevelId)
        assertEquals(NodeStatus.LOCKED, state.node(3).status)
    }

    /** A level opens on its predecessor, not on the highest cleared level. */
    @Test
    fun aLevelOpensOnlyOnceTheOneBeforeItIsCleared() {
        val state = map(progress(crowns = mapOf(1 to 3, 3 to 3)))

        assertEquals(NodeStatus.AVAILABLE, state.node(2).status)
        assertEquals(NodeStatus.CLEARED, state.node(3).status)
        assertEquals(NodeStatus.AVAILABLE, state.node(4).status)
        assertEquals(NodeStatus.LOCKED, state.node(5).status)
        assertEquals("the lowest reachable level", 2, state.currentLevelId)
    }

    @Test
    fun positionsAndThePathComeFromTheLayout() {
        for ((index, slice) in map().slices.withIndex()) {
            val layout = MapLayout.sliceLayout(index, slice.levels.size)
            assertEquals(layout.nodes, slice.levels.map { it.position })
            assertEquals(listOf(layout.entry) + layout.nodes + listOf(layout.exit), slice.path)
            assertEquals(layout.sweetRoom, slice.sweetRoom!!.position)
            if (index > 0) assertEquals(layout.gate, slice.gate!!.position)
            assertEquals(DEFAULT_WORLDS[index].pegTintArgb, slice.pegTintArgb)
            assertEquals(30, slice.crownsAvailable)
        }
    }

    // ---------------------------------------------------------------- §6.2 gates

    @Test
    fun fourteenCrownsLeaveWorldTwoShutAndTheMarkerOnLevelTen() {
        val state = map(progress(crowns = FOURTEEN))

        val gate = state.gate(2)
        assertTrue(gate.levelCleared)
        assertEquals(14, gate.crownsHave)
        assertEquals(15, gate.crownsRequired)
        assertEquals(10, gate.requiresLevelCleared)
        assertFalse(gate.open)
        assertFalse(state.slice(2)!!.unlocked)
        assertEquals(NodeStatus.LOCKED, state.node(11).status)
        assertEquals("the frontier is behind a shut gate", 10, state.currentLevelId)
        assertEquals(0, state.currentSliceIndex)
    }

    @Test
    fun theFifteenthCrownOpensWorldTwo() {
        val state = map(progress(crowns = FOURTEEN + (10 to 3)))

        assertEquals(15, state.totalCrowns)
        assertTrue(state.gate(2).open)
        assertTrue(state.slice(2)!!.unlocked)
        assertEquals(NodeStatus.AVAILABLE, state.node(11).status)
        assertEquals(11, state.currentLevelId)
        assertEquals(1, state.currentSliceIndex)
        assertEquals(NodeStatus.LOCKED, state.node(12).status)
    }

    /** §6.2 budgets main-level crowns only; `PlayerProgress.totalCrowns` excludes Sweet Rooms. */
    @Test
    fun sweetRoomCrownsDoNotCountTowardAGate() {
        val state = map(
            progress(crowns = FOURTEEN + (101 to 3), jars = mapOf(CandyColor.GREEN to 200)),
        )

        assertEquals(NodeStatus.CLEARED, state.room(101).status)
        assertEquals(3, state.room(101).crowns)
        assertEquals(14, state.totalCrowns)
        assertEquals(14, state.gate(2).crownsHave)
        assertFalse(state.gate(2).open)
        assertEquals(NodeStatus.LOCKED, state.node(11).status)
    }

    @Test
    fun theLevelClearedHalfOfAGateMattersOnItsOwn() {
        val state = map(progress(crowns = cleared(1..9, 3)))

        val gate = state.gate(2)
        assertEquals(27, gate.crownsHave)
        assertFalse(gate.levelCleared)
        assertFalse(gate.open)
        assertEquals(NodeStatus.AVAILABLE, state.node(10).status)
        assertEquals(NodeStatus.LOCKED, state.node(11).status)
        assertEquals(10, state.currentLevelId)
    }

    /** A config update that raises a gate must not take away a level the player already beat. */
    @Test
    fun aClearedLevelStaysClearedWhenATightenedGateShutsItsWorld() {
        val tightened = gatesConfig(worldTwoGate(crownsRequired = 30))
        val state = map(progress(crowns = cleared(1..10, 2) + (11 to 1)), tightened)

        assertEquals(21, state.totalCrowns)
        assertFalse(state.gate(2).open)
        assertFalse(state.slice(2)!!.unlocked)
        assertEquals(NodeStatus.CLEARED, state.node(11).status)
        assertEquals(1, state.node(11).crowns)
        assertTrue(state.node(11).isPlayable)
        assertEquals(NodeStatus.LOCKED, state.node(12).status)
        assertEquals(11, state.currentLevelId)
        assertEquals(1, state.currentSliceIndex)
    }

    @Test
    fun aConfiguredGateIsHonouredAndAMissingOneFallsBackToTheDefault() {
        val lenient = gatesConfig(worldTwoGate(crownsRequired = 5))
        val tenCrowns = progress(crowns = cleared(1..10, 1))
        val state = map(tenCrowns, lenient)

        assertEquals(5, state.gate(2).crownsRequired)
        assertTrue(state.gate(2).open)
        assertEquals(NodeStatus.AVAILABLE, state.node(11).status)
        assertEquals(11, state.currentLevelId)
        // Worlds 3 and 4 are missing from this config: §6.2's defaults, never "open by omission".
        assertEquals(20 to 35, state.gate(3).requiresLevelCleared to state.gate(3).crownsRequired)
        assertEquals(30 to 55, state.gate(4).requiresLevelCleared to state.gate(4).crownsRequired)
        // The same save under the shipped gate stays shut.
        assertFalse(map(tenCrowns).gate(2).open)
    }

    @Test
    fun worldOneIsAlwaysOpenAndAWorldWithNoGateAnywhereNeverIs() {
        val everything = progress(crowns = cleared(1..40, 3))
        val gatedWorldOne = gatesConfig(*DEFAULT_GATES.toTypedArray(), GateDef(1, 40, 999, 999))

        assertTrue(MapStateMapper.isWorldOpen(1, PlayerProgress(), null))
        assertTrue(MapStateMapper.isWorldOpen(1, PlayerProgress(), gatedWorldOne))
        for (world in listOf(0, -1, 5)) {
            assertFalse("World $world", MapStateMapper.isWorldOpen(world, everything, null))
        }
        // An empty gate list still falls back to §6.2 rather than opening everything.
        val noGates = gatesConfig()
        assertFalse(MapStateMapper.isWorldOpen(2, progress(crowns = cleared(1..10, 1)), noGates))
        assertTrue(MapStateMapper.isWorldOpen(4, everything, noGates))
    }

    // ---------------------------------------------------------------- §6.1 worlds

    @Test
    fun aMalformedWorldListFallsBackToTheDefaultsWholesale() {
        val w = DEFAULT_WORLDS
        val malformed = mapOf(
            "three worlds" to w.take(3),
            "five worlds" to w + w[3].copy(index = 5, levelFrom = 41, levelTo = 44),
            "a duplicate index" to listOf(w[0], w[1], w[2].copy(index = 2), w[3]),
            "zero-based indices" to w.map { it.copy(index = it.index - 1) },
            "a gap" to listOf(w[0], w[1].copy(levelFrom = 12), w[2], w[3]),
            "an overlap" to listOf(w[0], w[1].copy(levelFrom = 10), w[2], w[3]),
            "an empty world" to
                listOf(w[0].copy(levelTo = 0), w[1].copy(levelFrom = 1), w[2], w[3]),
            "not starting at level 1" to listOf(w[0].copy(levelFrom = 2), w[1], w[2], w[3]),
            "stopping short of level 40" to listOf(w[0], w[1], w[2], w[3].copy(levelTo = 39)),
            "running past level 40" to listOf(w[0], w[1], w[2], w[3].copy(levelTo = 44)),
            "no worlds at all" to emptyList(),
        )
        for ((why, worlds) in malformed) {
            val config = GameConfig(worlds = worlds)
            assertEquals(why, DEFAULT_WORLDS, MapStateMapper.worlds(config))
            assertEquals(
                why,
                listOf(1..10, 11..20, 21..30, 31..40),
                map(config = config).slices.map { it.levelFrom..it.levelTo },
            )
        }
    }

    /** How the 40 levels split between the four worlds is the config's call, in any list order. */
    @Test
    fun aWellFormedWorldListIsUsedInIndexOrderWhateverItsSplit() {
        val retuned = listOf(
            DEFAULT_WORLDS[3],
            DEFAULT_WORLDS[1].copy(levelFrom = 13),
            DEFAULT_WORLDS[0].copy(levelTo = 12, pegTint = "#123456"),
            DEFAULT_WORLDS[2],
        )
        val config = GameConfig(worlds = retuned)

        assertEquals(listOf(1, 2, 3, 4), MapStateMapper.worlds(config).map { it.index })
        val state = map(config = config)
        val first = state.slice(1)!!
        assertEquals(1..12, first.levelFrom..first.levelTo)
        assertEquals(36, first.crownsAvailable)
        assertEquals(0xFF123456.toInt(), first.pegTintArgb)
        assertEquals(MapLayout.sliceLayout(0, 12).nodes, first.levels.map { it.position })
        assertEquals(8, state.slice(2)!!.levels.size)
        assertEquals(120, state.maxCrowns)
    }

    @Test
    fun aNullConfigMapsAgainstTheDefaults() {
        assertEquals(map(config = GameConfig.DEFAULTS), map(config = null))
        assertEquals(DEFAULT_WORLDS, MapStateMapper.worlds(null))
    }

    // ---------------------------------------------------------------- §7 Sweet Rooms

    @Test
    fun eachSliceCarriesItsOwnSweetRoom() {
        val rooms = map().slices.map { it.sweetRoom!! }

        assertEquals(listOf(101, 102, 103, 104), rooms.map { it.levelId })
        assertEquals(listOf("B1", "B2", "B3", "B4"), rooms.map { it.code })
        assertEquals(
            listOf(CandyColor.GREEN, CandyColor.PURPLE, CandyColor.PINK, CandyColor.BLUE),
            rooms.map { it.jarColor },
        )
        assertEquals(List(4) { 200 }, rooms.map { it.jarThreshold })
    }

    @Test
    fun aSweetRoomOpensWhenItsOwnJarReachesTierThree() {
        val almost = map(progress(jars = mapOf(CandyColor.GREEN to 199))).room(101)
        assertEquals(NodeStatus.LOCKED, almost.status)
        assertEquals(199, almost.jarCount)
        assertEquals(200, almost.jarThreshold)

        val full = map(progress(jars = mapOf(CandyColor.GREEN to 200))).room(101)
        assertEquals(NodeStatus.AVAILABLE, full.status)
        assertEquals(200, full.jarCount)

        val otherJar = map(progress(jars = mapOf(CandyColor.BLUE to 500))).room(101)
        assertEquals("only B1's own jar opens it", NodeStatus.LOCKED, otherJar.status)
    }

    @Test
    fun theSweetRoomThresholdFollowsTheConfig() {
        val config = thresholdsConfig(5, 10, 15)

        val below = map(progress(jars = mapOf(CandyColor.PINK to 14)), config).room(103)
        assertEquals(NodeStatus.LOCKED, below.status)
        assertEquals(15, below.jarThreshold)
        val reached = map(progress(jars = mapOf(CandyColor.PINK to 15)), config).room(103)
        assertEquals(NodeStatus.AVAILABLE, reached.status)

        // An unsorted override is read the jar screen's way — sorted — so tier 3 is still 15.
        assertEquals(15, map(config = thresholdsConfig(15, 5, 10)).room(103).jarThreshold)
    }

    /** D1/D2 note 13: the map and the jar screen resolve tier 3 with the very same code. */
    @Test
    fun theMapAndTheJarScreenAgreeOnEveryRoom() {
        for (config in listOf(null, thresholdsConfig(5, 10, 15))) {
            for (count in listOf(0, 14, 15, 199, 200, 5_000)) {
                val jars = CandyColor.entries.associateWith { count }
                val unlocked = JarUiStateMapper.unlockedSweetRooms(jars, config?.jar)
                for (room in map(progress(jars = jars), config).slices.map { it.sweetRoom!! }) {
                    assertEquals("${room.code} at $count", room.levelId in unlocked, room.isPlayable)
                }
            }
        }
    }

    @Test
    fun aSweetRoomIsPlayableOnAWorldThatIsStillShut() {
        val blueFull = progress(jars = mapOf(CandyColor.BLUE to 200))
        val state = map(blueFull)
        val slice = state.slice(4)!!

        assertFalse(slice.unlocked)
        val room = checkNotNull(slice.sweetRoom)
        assertEquals(104, room.levelId)
        assertEquals("B4", room.code)
        assertEquals(CandyColor.BLUE, room.jarColor)
        assertEquals(NodeStatus.AVAILABLE, room.status)
        assertTrue(room.isPlayable)
        assertEquals(NodeStatus.AVAILABLE, MapStateMapper.statusOf(104, blueFull, null))
        assertEquals("the marker stays on the main road", 1, state.currentLevelId)
    }

    @Test
    fun aSweetRoomShowsItsCrownsClampedOnceCleared() {
        val purpleFull = progress(crowns = mapOf(102 to 9), jars = mapOf(CandyColor.PURPLE to 200))

        val open = map(purpleFull).room(102)
        assertEquals(NodeStatus.CLEARED, open.status)
        assertEquals(3, open.crowns)
    }

    /** Like a main level behind a gate: content the player has beaten never locks again. */
    @Test
    fun aClearedSweetRoomStaysPlayableWhenARetunedConfigLiftsTierThreeOutOfReach() {
        val purpleFull = progress(crowns = mapOf(102 to 2), jars = mapOf(CandyColor.PURPLE to 200))
        val retuned = thresholdsConfig(40, 120, 400)

        val room = map(purpleFull, retuned).room(102)
        assertEquals(NodeStatus.CLEARED, room.status)
        assertEquals(2, room.crowns)
        assertEquals(NodeStatus.CLEARED, MapStateMapper.statusOf(102, purpleFull, retuned))

        // A room never beaten is still the jar's to open.
        val unbeaten = progress(jars = mapOf(CandyColor.PURPLE to 200))
        assertEquals(NodeStatus.LOCKED, map(unbeaten, retuned).room(102).status)
    }

    @Test
    fun aRoomBelongsToWhicheverJarTheConfigPointsAtIt() {
        val swapped = jarsConfig(
            JarDef(CandyColor.GREEN, BallSkin.GREEN, TrailType.GREEN, "B4", 104),
            JarDef(CandyColor.BLUE, BallSkin.BLUE, TrailType.BLUE, "B1", 101),
        )
        val state = map(progress(jars = mapOf(CandyColor.BLUE to 200)), swapped)

        assertEquals(CandyColor.BLUE, state.room(101).jarColor)
        assertEquals(NodeStatus.AVAILABLE, state.room(101).status)
        assertEquals(CandyColor.GREEN, state.room(104).jarColor)
        assertEquals(NodeStatus.LOCKED, state.room(104).status)
        // Purple and Pink are not listed, so they keep §7's rooms.
        assertEquals(CandyColor.PURPLE, state.room(102).jarColor)
        assertEquals(CandyColor.PINK, state.room(103).jarColor)
    }

    /** A room no jar resolves to could never open, so it is not drawn at all. */
    @Test
    fun aRoomNoJarReachesIsLeftOffTheMap() {
        // Blue now opens B1 — as Green, unlisted, still does by §7 — so no jar is left for B4.
        val orphaning = jarsConfig(JarDef(CandyColor.BLUE, BallSkin.BLUE, TrailType.BLUE, "B1", 101))
        val allFull = progress(jars = CandyColor.entries.associateWith { 500 })
        val state = map(allFull, orphaning)

        assertNull(state.slice(4)!!.sweetRoom)
        assertEquals(NodeStatus.LOCKED, MapStateMapper.statusOf(104, allFull, orphaning))
        assertEquals(NodeStatus.AVAILABLE, state.room(101).status)
    }

    // ---------------------------------------------------------------- crowns, scores, the marker

    @Test
    fun crownsAreClampedIntoZeroToThree() {
        val corrupt = progress(crowns = mapOf(1 to 7, 2 to -2))
        val state = map(corrupt)

        assertEquals(3, state.node(1).crowns)
        assertEquals(NodeStatus.CLEARED, state.node(1).status)
        assertEquals(0, state.node(2).crowns)
        assertEquals(NodeStatus.AVAILABLE, state.node(2).status)
        assertEquals(3, state.slice(1)!!.crownsEarned)
        // The header figure is §6.2's own number, PlayerProgress.totalCrowns, passed through.
        assertEquals(corrupt.totalCrowns, state.totalCrowns)
    }

    @Test
    fun bestScoresAreCarriedThrough() {
        val best = mapOf(1 to 4_200, 2 to 900, 101 to 77)
        val state = map(progress(crowns = mapOf(1 to 2), best = best))

        assertEquals(4_200, state.node(1).bestScore)
        // A failed attempt still files a best score, so an uncleared level can carry one.
        assertEquals(900, state.node(2).bestScore)
        assertEquals(77, state.room(101).bestScore)
    }

    @Test
    fun exactlyOneNodeCarriesTheMarker() {
        for (scenario in SCENARIOS) {
            val state = map(scenario.progress, scenario.config)
            val marked = state.allLevels.filter { it.isCurrent }
            assertEquals(scenario.why, 1, marked.size)
            assertEquals(scenario.why, state.currentLevelId, marked.single().levelId)
            assertEquals(
                scenario.why,
                state.currentSliceIndex,
                state.slices.indexOfFirst { slice -> slice.levels.any { it.isCurrent } },
            )
        }
    }

    @Test
    fun withEverythingClearedTheMarkerRestsOnTheLastLevel() {
        val state = map(progress(crowns = cleared(1..40, 3)))

        assertEquals(40, state.currentLevelId)
        assertEquals(3, state.currentSliceIndex)
        assertTrue(state.allLevels.all { it.status == NodeStatus.CLEARED })
        assertTrue(state.slices.all { it.unlocked })
        assertTrue((2..4).all { state.gate(it).open })
        assertEquals(120, state.totalCrowns)
        assertEquals(120, state.maxCrowns)
        assertEquals(List(4) { 30 }, state.slices.map { it.crownsEarned })
    }

    // ---------------------------------------------------------------- statusOf

    /** `LevelMapViewModel.openLevel` trusts `statusOf`, so it must never disagree with the map. */
    @Test
    fun statusOfAgreesWithTheMapOnEveryNode() {
        for ((why, progress, config) in SCENARIOS) {
            val state = map(progress, config)
            fun statusOf(id: Int) = MapStateMapper.statusOf(id, progress, config)
            for (node in state.allLevels) {
                assertEquals("$why: L${node.levelId}", node.status, statusOf(node.levelId))
            }
            for (room in state.slices.mapNotNull { it.sweetRoom }) {
                assertEquals("$why: ${room.code}", room.status, statusOf(room.levelId))
            }
        }
    }

    @Test
    fun anIdOffTheMapIsLocked() {
        val everything = progress(
            crowns = cleared(1..40, 3) + mapOf(0 to 3, 41 to 3, 105 to 3),
            jars = CandyColor.entries.associateWith { 1_000 },
        )
        for (id in listOf(0, -1, 41, 100, 105, 999, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals("id $id", NodeStatus.LOCKED, MapStateMapper.statusOf(id, everything, null))
        }
    }

    // ---------------------------------------------------------------- the shipped config.json

    /**
     * The real `assets/config.json`, through the production `ConfigLoader`: an edit that breaks the
     * map — a world list that falls back, a gate that can never open, a Sweet Room no jar reaches,
     * a world too crowded to draw, a node the catalogue cannot open — fails here.
     */
    @Test
    fun theShippedConfigDrawsASoundMap() {
        val config = RealLevels.config
        assertEquals(
            "the config's own worlds, not a silent fallback",
            config.worlds.sortedBy { it.index },
            MapStateMapper.worlds(config),
        )

        val state = map(config = config)
        assertEquals(40, state.allLevels.size)
        assertEquals(1, state.currentLevelId)

        val layouts = state.slices.mapIndexed { i, s -> MapLayout.sliceLayout(i, s.levels.size) }
        for ((i, layout) in layouts.withIndex()) {
            val problems = SliceFootprints.clashes(layout) + SliceFootprints.outOfBounds(layout)
            assertTrue("slice $i: $problems", problems.isEmpty())
        }
        for (k in 0 until layouts.size - 1) {
            val clashes = SliceFootprints.seamClashes(layouts[k], layouts[k + 1])
            assertTrue("seam $k|${k + 1}: $clashes", clashes.isEmpty())
        }

        // A gate that asks for a level ahead of it, or more crowns than exist before it, never opens.
        for (slice in state.slices.drop(1)) {
            val gate = checkNotNull(slice.gate)
            val world = "World ${slice.world}'s gate"
            assertTrue("$world: level", gate.requiresLevelCleared in 1 until slice.levelFrom)
            assertTrue("$world: crowns", gate.crownsRequired <= 3 * (slice.levelFrom - 1))
        }

        val rooms = state.slices.map { checkNotNull(it.sweetRoom) { "no Sweet Room on ${it.world}" } }
        assertEquals("one jar per room", 4, rooms.map { it.jarColor }.toSet().size)

        for (slice in state.slices) {
            for (node in slice.levels) {
                val level = RealLevels.catalog.level(node.levelId)
                assertNotNull("L${node.levelId} is not in levels.json", level)
                assertEquals("L${node.levelId}'s world", slice.world, level!!.world)
            }
        }
        for (room in rooms) {
            assertNotNull("${room.code} is not in levels.json", RealLevels.catalog.level(room.levelId))
        }
    }

    /** Three crowns everywhere clears every gate the moment it is reached: nothing ever stalls. */
    @Test
    fun aThreeCrownRunThroughTheShippedConfigNeverStalls() {
        val config = RealLevels.config
        var crowns = emptyMap<Int, Int>()
        for (id in 1..40) {
            val state = map(progress(crowns = crowns), config)
            assertEquals("before clearing L$id", id, state.currentLevelId)
            assertEquals("L$id", NodeStatus.AVAILABLE, state.node(id).status)
            crowns = crowns + (id to 3)
        }
        assertEquals(40, map(progress(crowns = crowns), config).currentLevelId)
    }

    // ---------------------------------------------------------------- helpers

    private fun map(progress: PlayerProgress = PlayerProgress(), config: GameConfig? = null) =
        MapStateMapper.map(progress, config)

    private fun LevelMapUiState.node(id: Int): LevelNodeUiState =
        allLevels.single { it.levelId == id }

    private fun LevelMapUiState.gate(world: Int): GateUiState = checkNotNull(slice(world)?.gate)

    private fun LevelMapUiState.room(id: Int): SweetRoomNodeUiState =
        slices.mapNotNull { it.sweetRoom }.single { it.levelId == id }

    private data class Scenario(
        val why: String,
        val progress: PlayerProgress,
        val config: GameConfig? = null,
    )

    private companion object {

        fun progress(
            crowns: Map<Int, Int> = emptyMap(),
            jars: Map<CandyColor, Int> = emptyMap(),
            best: Map<Int, Int> = emptyMap(),
        ) = PlayerProgress(
            crownsByLevel = crowns,
            bestScoreByLevel = best,
            jarCounts = PlayerProgress.EMPTY_JARS + jars,
        )

        /** Levels [ids], each cleared with [crowns]. */
        fun cleared(ids: IntRange, crowns: Int): Map<Int, Int> = ids.associateWith { crowns }

        /** World 2's §6.2 gate (level 10 cleared), asking for [crownsRequired] instead of 15. */
        fun worldTwoGate(crownsRequired: Int) = GateDef(
            world = 2,
            requiresLevelCleared = 10,
            crownsRequired = crownsRequired,
            maxAvailableCrowns = 30,
        )

        /** A config whose gate list is exactly [gates]; every other section is the default. */
        fun gatesConfig(vararg gates: GateDef) = GameConfig(gates = gates.toList())

        /** A config whose jar list is exactly [jars]; unlisted colours keep §7's rewards. */
        fun jarsConfig(vararg jars: JarDef) = GameConfig(jar = JarConfig(jars = jars.toList()))

        /** A config whose jar tiers sit at [thresholds] instead of §7's 40 / 120 / 200. */
        fun thresholdsConfig(vararg thresholds: Int) =
            GameConfig(jar = JarConfig(tierThresholds = thresholds.toList()))

        /** Levels 1..10 all cleared, 14 crowns between them — one short of World 2. */
        val FOURTEEN: Map<Int, Int> = cleared(1..6, 1) + cleared(7..10, 2)

        val SCENARIOS: List<Scenario> = listOf(
            Scenario("fresh install", PlayerProgress()),
            Scenario("level 1 cleared", progress(crowns = mapOf(1 to 1))),
            Scenario("gap in the middle", progress(crowns = mapOf(1 to 3, 3 to 3))),
            Scenario("one crown short", progress(crowns = FOURTEEN)),
            Scenario("World 2 open", progress(crowns = FOURTEEN + (10 to 3))),
            Scenario("gate level missing", progress(crowns = cleared(1..9, 3))),
            Scenario(
                "tightened gate",
                progress(crowns = cleared(1..10, 2) + (11 to 1)),
                gatesConfig(worldTwoGate(crownsRequired = 30)),
            ),
            Scenario("corrupt crowns", progress(crowns = mapOf(1 to 7, 2 to -2))),
            Scenario(
                "Sweet Rooms open",
                progress(
                    crowns = mapOf(1 to 2, 103 to 1),
                    jars = CandyColor.entries.associateWith { 250 },
                ),
            ),
            Scenario("everything cleared", progress(crowns = cleared(1..40, 3))),
        )
    }
}
