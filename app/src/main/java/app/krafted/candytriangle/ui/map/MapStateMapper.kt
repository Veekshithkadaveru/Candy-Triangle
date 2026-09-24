package app.krafted.candytriangle.ui.map

import app.krafted.candytriangle.data.JarUnlocks
import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.DEFAULT_GATES
import app.krafted.candytriangle.level.DEFAULT_WORLDS
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.GateDef
import app.krafted.candytriangle.level.JarConfig
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.level.WorldDef
import app.krafted.candytriangle.ui.jar.JarReward
import app.krafted.candytriangle.ui.jar.JarUiState
import app.krafted.candytriangle.ui.jar.JarUiStateMapper

/**
 * Turns §10 progress plus the §6.1/§6.2/§7 config into the level map.
 *
 * Every lock decision on the map lives here, where the JVM suite can reach it:
 *
 * - **Main levels.** Level 1 is always reachable. Level `n` is reachable once level `n - 1` is
 *   cleared *and* level `n`'s world is open. A cleared level (>= 1 crown) is always
 *   [NodeStatus.CLEARED], whatever its gate says now.
 * - **World gates (§6.2).** World 1 is open. World `w` opens when `gateFor(w).requiresLevelCleared`
 *   is cleared **and** `PlayerProgress.totalCrowns >= crownsRequired` (main levels only).
 * - **Sweet Rooms (§7).** Reachable when their jar has reached tier 3, resolved the same way the
 *   jar screen resolves it (`JarUiStateMapper`, config first), so the two screens never disagree
 *   about whether B3 has been earned. World gates do not apply. As with main levels, a room once
 *   beaten stays [NodeStatus.CLEARED] even if a retuned config lifts tier 3 back out of reach.
 *
 * `config == null` means "not parsed yet" and maps against `GameConfig.DEFAULTS`, the same
 * convention as `JarUiStateMapper`.
 *
 * ## The finer rules
 *
 * - **Crowns are coerced into 0..3** wherever a node shows them, so a hand-edited save cannot draw
 *   five crowns. A node shows crowns only while it is [NodeStatus.CLEARED], which for every node
 *   means "has at least one crown".
 *   [LevelMapUiState.totalCrowns] and the gates, by contrast, read `PlayerProgress.totalCrowns`
 *   verbatim: that is the number §6.2 compares, and the one `GateUiState.crownsHave` documents.
 * - **A gate is never open by omission.** A world whose gate the config leaves out falls back to
 *   §6.2's `DEFAULT_GATES` entry for it; a world with no gate even there stays shut.
 * - **The marker** sits on the lowest [NodeStatus.AVAILABLE] main level; failing that (everything
 *   cleared, or the frontier is behind a shut gate) on the highest [NodeStatus.CLEARED] one; on a
 *   fresh install, on the first level. Exactly one node carries it.
 * - **A Sweet Room's jar** is whichever jar's tier-3 reward the jar screen resolves to that room.
 *   A room no jar resolves to can never open, so its slice shows no Sweet Room at all (`null`).
 *
 * Owner: D3 Agent A (map logic).
 */
object MapStateMapper {

    /** The whole map. */
    fun map(progress: PlayerProgress, config: GameConfig?): LevelMapUiState {
        val rules = Rules(progress, config)
        val statuses = LinkedHashMap<Int, NodeStatus>()
        for (world in rules.worlds) {
            for (levelId in world.levelFrom..world.levelTo) {
                statuses[levelId] = rules.mainStatus(levelId)
            }
        }
        // Worlds are sorted and contiguous (see worlds()), so `statuses` iterates in id order.
        val current = statuses.entries.firstOrNull { it.value == NodeStatus.AVAILABLE }?.key
            ?: statuses.entries.lastOrNull { it.value == NodeStatus.CLEARED }?.key
            ?: rules.firstMainLevel
        val slices = rules.worlds.mapIndexed { index, world ->
            slice(index, world, rules, statuses, current)
        }
        return LevelMapUiState(
            slices = slices,
            totalCrowns = progress.totalCrowns,
            maxCrowns = ProgressStore.MAX_CROWNS_PER_LEVEL * statuses.size,
            currentLevelId = current,
            currentSliceIndex = slices.indexOfFirst { current in it.levelFrom..it.levelTo }
                .coerceAtLeast(0),
            loaded = true,
        )
    }

    /**
     * The status of one node — a main level (1..40) or a Sweet Room (101..104); anything else is
     * [NodeStatus.LOCKED]. `LevelMapViewModel.openLevel` re-derives this from the live store rather
     * than trusting a UI snapshot, the same way `JarViewModel.equipSkin` re-derives its gate.
     */
    fun statusOf(levelId: Int, progress: PlayerProgress, config: GameConfig?): NodeStatus = when {
        LevelIds.isMain(levelId) -> Rules(progress, config).mainStatus(levelId)
        LevelIds.isBonus(levelId) -> Rules(progress, config).sweetRoomStatus(levelId)
        else -> NodeStatus.LOCKED
    }

    /** Whether world [world] (1..4) is open under §6.2. */
    fun isWorldOpen(world: Int, progress: PlayerProgress, config: GameConfig?): Boolean {
        if (world == FIRST_WORLD) return true
        val gate = gateFor(world, config) ?: return false
        return progress.isCleared(gate.requiresLevelCleared) &&
            progress.totalCrowns >= gate.crownsRequired
    }

    /**
     * The four worlds the map draws, in index order: `config.worlds` when it describes exactly
     * worlds 1..4, else `DEFAULT_WORLDS` wholesale (a malformed list falls back, it is not patched).
     *
     * "Describes" means: after sorting by index, exactly worlds 1..[MapLayout.SLICE_COUNT], each
     * holding at least one level, together covering main levels [LevelIds.MAIN_FIRST]..
     * [LevelIds.MAIN_LAST] contiguously and in order. Anything less cannot be drawn honestly — a
     * missing world leaves a backdrop with no road, a gap strands the level after it (its
     * predecessor is on no slice, so it could never open), an overlap draws a level twice, and ids
     * past [LevelIds.MAIN_LAST] are not main levels to §10's crown count. How the levels split
     * between worlds is the config's call; `MapLayout` holds up to `NODE_CAPACITY` per slice
     * cleanly.
     */
    fun worlds(config: GameConfig?): List<WorldDef> {
        val configured = (config ?: GameConfig.DEFAULTS).worlds.sortedBy { it.index }
        return if (describesTheMap(configured)) configured else DEFAULT_WORLDS
    }

    // ------------------------------------------------------------------ internals

    /** §6.2: World 1 has no gate. */
    private const val FIRST_WORLD = 1

    private fun describesTheMap(sorted: List<WorldDef>): Boolean {
        if (sorted.size != MapLayout.SLICE_COUNT) return false
        var nextLevel = LevelIds.MAIN_FIRST
        for ((i, world) in sorted.withIndex()) {
            if (world.index != FIRST_WORLD + i) return false
            if (world.levelFrom != nextLevel || world.levelTo < world.levelFrom) return false
            nextLevel = world.levelTo + 1
        }
        return nextLevel == LevelIds.MAIN_LAST + 1
    }

    /** The gate guarding [world]: the config's, else §6.2's default for that world, else none. */
    private fun gateFor(world: Int, config: GameConfig?): GateDef? =
        (config ?: GameConfig.DEFAULTS).gateFor(world)
            ?: DEFAULT_GATES.firstOrNull { it.world == world }

    private fun slice(
        index: Int,
        world: WorldDef,
        rules: Rules,
        statuses: Map<Int, NodeStatus>,
        current: Int,
    ): WorldSliceUiState {
        val layout = MapLayout.sliceLayout(index, world.levelTo - world.levelFrom + 1)
        val levels = (world.levelFrom..world.levelTo).mapIndexed { i, levelId ->
            LevelNodeUiState(
                levelId = levelId,
                status = statuses.getValue(levelId),
                // Non-zero exactly when CLEARED: that status *is* "at least one crown".
                crowns = rules.crowns(levelId),
                bestScore = rules.bestScore(levelId),
                isCurrent = levelId == current,
                position = layout.nodes[i],
            )
        }
        return WorldSliceUiState(
            world = world.index,
            levelFrom = world.levelFrom,
            levelTo = world.levelTo,
            pegTintArgb = world.pegTintArgb,
            unlocked = rules.isOpen(world.index),
            crownsEarned = levels.sumOf { it.crowns },
            crownsAvailable = ProgressStore.MAX_CROWNS_PER_LEVEL * levels.size,
            gate = if (world.index == FIRST_WORLD) null else rules.gate(world.index, layout.gate),
            levels = levels,
            sweetRoom = rules.sweetRoom(LevelIds.BONUS_FIRST + index, layout.sweetRoom),
            path = buildList {
                add(layout.entry)
                addAll(layout.nodes)
                add(layout.exit)
            },
        )
    }

    /**
     * One mapping pass's resolved inputs — the worlds, which of them are open, the jars — shared by
     * [map] and [statusOf] so the map and the tap gate cannot drift apart.
     */
    private class Rules(private val progress: PlayerProgress, private val config: GameConfig?) {

        val worlds: List<WorldDef> = worlds(config)

        val firstMainLevel: Int = worlds.first().levelFrom

        private val openWorlds: Set<Int> =
            worlds.map { it.index }.filter { isWorldOpen(it, progress, config) }.toSet()

        private val jarConfig: JarConfig? = config?.jar

        /** The jar screen's resolved thresholds: config first, §7's table as the fallback. */
        private val thresholds: List<Int> = JarUiStateMapper.thresholds(jarConfig)

        /** Tier 3 of [thresholds] — what fills a jar far enough to open its Sweet Room. */
        val sweetRoomThreshold: Int = thresholds[JarUnlocks.MAX_TIER - 1]

        /** The jar screen's own answer, so the two screens agree on every room (D1/D2 note 13). */
        private val unlockedSweetRooms: Set<Int> =
            JarUiStateMapper.unlockedSweetRooms(progress.jarCounts, jarConfig)

        /** Each jar as the jar screen maps it — which is where a room's owning jar is read from. */
        private val jars: List<JarUiState> = CandyColor.entries.map { color ->
            JarUiStateMapper.mapJar(color, progress.jarCount(color), thresholds, jarConfig)
        }

        fun isOpen(world: Int): Boolean = world in openWorlds

        fun crowns(levelId: Int): Int =
            progress.crownsFor(levelId).coerceIn(0, ProgressStore.MAX_CROWNS_PER_LEVEL)

        fun bestScore(levelId: Int): Int = progress.bestScoreFor(levelId).coerceAtLeast(0)

        fun mainStatus(levelId: Int): NodeStatus {
            if (crowns(levelId) >= 1) return NodeStatus.CLEARED
            if (levelId == firstMainLevel) return NodeStatus.AVAILABLE
            val world = worlds.firstOrNull { levelId in it.levelFrom..it.levelTo }
            val reachable = world != null && isOpen(world.index) && progress.isCleared(levelId - 1)
            return if (reachable) NodeStatus.AVAILABLE else NodeStatus.LOCKED
        }

        /**
         * Crowns first, like [mainStatus]: a room once beaten stays [NodeStatus.CLEARED] — replayable —
         * even if a retuned config later lifts tier 3 back out of its jar's reach. Only a room never
         * beaten is gated by the jar.
         */
        fun sweetRoomStatus(levelId: Int): NodeStatus = when {
            crowns(levelId) >= 1 -> NodeStatus.CLEARED
            levelId !in unlockedSweetRooms -> NodeStatus.LOCKED
            else -> NodeStatus.AVAILABLE
        }

        fun gate(world: Int, position: MapPoint): GateUiState? {
            val def = gateFor(world, config) ?: return null
            return GateUiState(
                world = world,
                requiresLevelCleared = def.requiresLevelCleared,
                levelCleared = progress.isCleared(def.requiresLevelCleared),
                crownsRequired = def.crownsRequired,
                crownsHave = progress.totalCrowns,
                open = isOpen(world),
                position = position,
            )
        }

        fun sweetRoom(levelId: Int, position: MapPoint): SweetRoomNodeUiState? {
            val code = LevelIds.bonusCode(levelId) ?: return null
            val jar = jars.firstOrNull { it.tier3SweetRoomId() == levelId } ?: return null
            val status = sweetRoomStatus(levelId)
            return SweetRoomNodeUiState(
                levelId = levelId,
                code = code,
                status = status,
                crowns = if (status == NodeStatus.CLEARED) crowns(levelId) else 0,
                bestScore = bestScore(levelId),
                jarColor = jar.color,
                jarCount = jar.count,
                jarThreshold = sweetRoomThreshold,
                position = position,
            )
        }

        private fun JarUiState.tier3SweetRoomId(): Int? =
            (reward(JarUnlocks.MAX_TIER)?.reward as? JarReward.SweetRoom)?.levelId
    }
}
