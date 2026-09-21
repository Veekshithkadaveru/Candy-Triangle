package app.krafted.candytriangle.level

import kotlin.math.roundToInt

/**
 * Domain models for `assets/config.json` — the whole of PRD §3.2-§7 in one tree.
 *
 * ## Why two layers
 *
 * Every type here comes in a pair: a nullable-field mirror of the JSON under [ConfigJson] (bottom
 * of this file, Gson's reflection target), and a public domain class with **non-null** fields (top
 * of this file, what the engine consumes). Hand-written `toDomain()` mappers bridge them,
 * substituting a documented default for anything missing or malformed.
 *
 * The game ships fully offline with no remote config and no recovery path, so a typo in a
 * hand-authored JSON file must degrade one value — never throw on the launch path. That is why
 * nothing in this file can fail: no `!!`, no required key, no custom Gson adapter.
 *
 * ## Why the defaults live on the domain classes
 *
 * Each domain class carries the §3.2/§6.1 table value as its constructor default. That gives us
 * [GameConfig.DEFAULTS] for free (the fallback used when the asset is absent or corrupt) and gives
 * every mapper an obvious, single-sourced fallback via `defaults.copy(x = dtoX ?: defaults.x)`.
 *
 * ## Units
 *
 * [Float] for anything the physics engine touches (it is float-based end to end); [Int] for
 * counts, scores and multipliers, which are exact by definition.
 */

// ---------------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------------

/**
 * The parsed `config.json` tree, plus the derived lookups later phases need.
 *
 * The per-level helpers ([worldFor], [spacingFor], [ballsFor], [gem], [cupSpeedFor]) live here
 * rather than on `ConfigLoader` deliberately: they are pure functions of the loaded config, so
 * putting them here makes them synchronous and trivially testable, and lets the render loop call
 * them without touching a suspending API. `ConfigLoader` exposes suspending forwarders for callers
 * that have not loaded the config yet.
 */
data class GameConfig(
    val configVersion: Int = 1,
    val physics: PhysicsConfig = PhysicsConfig(),
    val board: BoardConfig = BoardConfig(),
    val cup: CupConfig = CupConfig(),
    val chains: ChainConfig = ChainConfig(),
    val scoring: ScoringConfig = ScoringConfig(),
    val gems: List<GemDef> = DEFAULT_GEMS,
    val clearance: ClearanceConfig = ClearanceConfig(),
    val worlds: List<WorldDef> = DEFAULT_WORLDS,
    val gates: List<GateDef> = DEFAULT_GATES,
    val jar: JarConfig = JarConfig(),
    val candies: CandyConfig = CandyConfig(),
) {

    /** The world owning [levelId] (§6.1), or `null` for a Sweet Room or an out-of-range id. */
    fun worldFor(levelId: Int): WorldDef? =
        worlds.firstOrNull { levelId in it.levelFrom..it.levelTo }

    /**
     * Lattice density `d` for [levelId], linearly interpolated across its world (§6.1).
     *
     * World 1 spans levels 1..10 at `90 -> 84`, so level 1 is 90, level 10 is 84 and level 5 lands
     * proportionally between. A level file may still override this per level (§6.3
     * `layout.spacing`); this is the curve a level inherits when it does not.
     *
     * Sweet Rooms and unknown ids have no world, and get [LatticeDef.spacingMax] — the most
     * forgiving lattice, appropriate for a bonus board with no difficulty curve.
     */
    fun spacingFor(levelId: Int): Float {
        val world = worldFor(levelId) ?: return board.lattice.spacingMax
        return lerp(world.spacingFrom, world.spacingTo, world.progressAt(levelId))
    }

    /**
     * Starting ball count for [levelId], interpolated across its world the same way as
     * [spacingFor] and rounded to the nearest whole ball.
     *
     * Sweet Rooms get [JarConfig.sweetRoomBalls] (§7: 15 balls); any other unknown id falls back to
     * the first world's opening count so a mis-keyed level is still playable.
     */
    fun ballsFor(levelId: Int): Int {
        val world = worldFor(levelId)
            ?: return if (LevelIds.isBonus(levelId)) {
                jar.sweetRoomBalls
            } else {
                worlds.firstOrNull()?.ballsFrom ?: DEFAULT_WORLDS.first().ballsFrom
            }
        return lerp(
            world.ballsFrom.toFloat(),
            world.ballsTo.toFloat(),
            world.progressAt(levelId),
        ).roundToInt()
    }

    /** The definition for [type] (§4.2), or `null` if the config dropped that gem entirely. */
    fun gem(type: GemType): GemDef? = gems.firstOrNull { it.type == type }

    /** Candy Cup speed for a 1-based [world] index (§6.1), falling back to the slowest lane. */
    fun cupSpeedFor(world: Int): Float =
        worlds.firstOrNull { it.index == world }?.cupSpeed ?: cup.speedMin

    /** The gate guarding a 1-based [world] index (§6.2), or `null` for World 1, which is open. */
    fun gateFor(world: Int): GateDef? = gates.firstOrNull { it.world == world }

    /** The jar for [color] (§7), or `null` if the config dropped it. */
    fun jarFor(color: CandyColor): JarDef? = jar.jars.firstOrNull { it.color == color }

    companion object {

        /**
         * The §3.2-§7 tables encoded in Kotlin, used verbatim when `config.json` is missing or
         * unparseable. Keeping a complete fallback (rather than throwing) means a bad config edit
         * costs the tuning, not the app.
         */
        val DEFAULTS: GameConfig = GameConfig()
    }
}

// ---------------------------------------------------------------------------
// §3.2 Physics
// ---------------------------------------------------------------------------

/** Tunable engine constants (§3.2) plus the §3.3 special-rule parameters. */
data class PhysicsConfig(
    /** Radius of the active ball collider, u. */
    val ballRadius: Float = 16f,
    /** Radius of a solid peg collider, u. */
    val pegRadius: Float = 7f,
    /** Sensor radius for candy collection, u. Candies are non-solid (§4.1). */
    val candyRadius: Float = 22f,
    /** Solid collider radius for gems, u. */
    val gemRadius: Float = 30f,
    /** Downward gravitational acceleration, u per second squared. */
    val gravity: Float = 1400f,
    /** Initial ball velocity magnitude at launch, u per second. */
    val launchSpeed: Float = 900f,
    /** Terminal speed clamp, u per second. Paired with [physicsStepMs] for §3.3 anti-tunnelling. */
    val maxBallSpeed: Float = 1600f,
    /** Restitution against pegs. */
    val restitutionPeg: Float = 0.60f,
    /** Restitution against gems — slightly livelier than pegs, so a gem hit reads as an event. */
    val restitutionGem: Float = 0.65f,
    /** Restitution against the slanted side walls. */
    val restitutionWall: Float = 0.50f,
    /** Tangential friction applied per collision contact. */
    val tangentialDamping: Float = 0.98f,
    /** Fixed physics timestep, ms. 4.1667 ms is the 240 Hz accumulator step. */
    val physicsStepMs: Float = 4.1667f,
    /** §11 determinism contract: the engine must be reproducible run to run. */
    val deterministic: Boolean = true,
    /** Kept at zero — any jitter would break the §11 determinism test. */
    val randomJitter: Float = 0f,
    val stuckBall: StuckBallConfig = StuckBallConfig(),
    val movingPegs: MovingPegConfig = MovingPegConfig(),
    val antiTunnelling: AntiTunnellingConfig = AntiTunnellingConfig(),
)

/** §3.3 stuck-ball rescue: a deterministic nudge, never a random one. */
data class StuckBallConfig(
    /** Speed below which a ball counts as stalled, u per second. */
    val speedThreshold: Float = 30f,
    /** How long it must stay stalled before the impulse fires, seconds. */
    val dwellSeconds: Float = 1.0f,
    /** Magnitude of the rescue impulse, u per second. */
    val impulseSpeed: Float = 120f,
    /** The central axis the impulse aims toward, u. Matches [BoardConfig.centerAxisX]. */
    val towardAxisX: Float = 500f,
    /** Whether the impulse also carries the ball downward, toward the open base. */
    val downward: Boolean = true,
)

/** §3.3 moving peg rows, introduced in World 3. */
data class MovingPegConfig(
    val introducedInWorld: Int = 3,
    /** Fraction of the peg's instantaneous horizontal velocity handed to the ball on contact. */
    val tangentialTransfer: Float = 0.50f,
    /** Documentation of the oscillation law; the engine implements it, it does not parse it. */
    val oscillation: String = "x(t) = x0 + A * sin(2 * PI * t / T)",
    /** Amplitude A, u. */
    val defaultAmplitude: Float = 40f,
    /** Period T, seconds. */
    val defaultPeriodSeconds: Float = 3.0f,
)

/**
 * §3.3 anti-tunnelling budget.
 *
 * At [PhysicsConfig.maxBallSpeed] the per-step displacement is 1600 / 240 = 6.67 u, strictly under
 * the 7 u peg radius — which is precisely why [sweptCollisionRequired] is false. These values are
 * config so the §11 tunnelling test can assert the invariant instead of trusting a comment.
 */
data class AntiTunnellingConfig(
    val maxDisplacementPerStep: Float = 6.67f,
    val smallestColliderRadius: Float = 7f,
    val sweptCollisionRequired: Boolean = false,
)

// ---------------------------------------------------------------------------
// §3.1 Board geometry
// ---------------------------------------------------------------------------

/** A point in logical board space. */
data class BoardPoint(val x: Float = 0f, val y: Float = 0f)

/** A horizontal band of the board, by y extent. */
data class YBand(val yTop: Float = 0f, val yBottom: Float = 0f)

/** A wall as a line segment from (x1, y1) to (x2, y2). */
data class WallLine(
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
)

/** §3.1 board geometry: a 1000 x 1250 isosceles triangle, apex up, base open. */
data class BoardConfig(
    val width: Float = 1000f,
    val height: Float = 1250f,
    /** Launcher pivot and triangle apex, (500, 0). */
    val apex: BoardPoint = BoardPoint(500f, 0f),
    /** Axis of symmetry; the stuck-ball impulse and the cup lane both key off it. */
    val centerAxisX: Float = 500f,
    val baseY: Float = 1250f,
    val baseWidth: Float = 1000f,
    /** The base is open — balls leave the board rather than bouncing off the bottom. */
    val baseOpen: Boolean = true,
    /** The band reserved for the launcher; no pegs are generated here. */
    val launcherZone: YBand = YBand(0f, 150f),
    /** The band that carries pegs, candies and gems. */
    val latticeBand: YBand = YBand(150f, 1170f),
    /** The Candy Cup's travel lane, below the open base. */
    val cupLane: YBand = YBand(1280f, 1330f),
    val walls: WallsConfig = WallsConfig(),
    val lattice: LatticeDef = LatticeDef(),
    val launcher: LauncherDef = LauncherDef(),
)

/** The two slanted side walls (§3.1), at 21.8 degrees from vertical. */
data class WallsConfig(
    val slantDegreesFromVertical: Float = 21.8f,
    val left: WallLine = WallLine(500f, 0f, 0f, 1250f),
    val right: WallLine = WallLine(500f, 0f, 1000f, 1250f),
)

/**
 * §3.1 peg lattice parameters.
 *
 * [spacingMin] 66 is what makes the §3.4 "ball between pegs" rule always satisfiable; see
 * [ClearanceConfig].
 */
data class LatticeDef(
    val type: String = "EQUILATERAL_TRIANGULAR",
    val spacingMin: Float = 66f,
    val spacingMax: Float = 90f,
    /** Row spacing is `d` times this — sqrt(3) over 2, the equilateral row height. */
    val rowSpacingFactor: Float = 0.8660254f,
    /** Nodes outside the triangle are discarded rather than clamped. */
    val clipToInterior: Boolean = true,
)

/** §3.1 launcher: fixed at the apex, aim clamped to plus or minus 70 degrees from straight down. */
data class LauncherDef(
    val pivot: BoardPoint = BoardPoint(500f, 0f),
    val aimClampDegrees: Float = 70f,
    val aimReference: String = "STRAIGHT_DOWN",
    val aimGuideStopsAtFirstContact: Boolean = true,
    val aimGuideDotted: Boolean = true,
    /** One ball in flight at a time — except while a Split Gem clone is alive (§4.2). */
    val maxSimultaneousBalls: Int = 1,
)

// ---------------------------------------------------------------------------
// Candy Cup
// ---------------------------------------------------------------------------

/** The moving Candy Cup below the open base (§3.2 `cupWidth`, §5.1 catch points). */
data class CupConfig(
    val cupWidth: Float = 180f,
    val catchScore: Int = 100,
    val catchRefundBalls: Int = 1,
    /** World 1 speed; [speedMax] is World 4's. Per-world values live on [WorldDef.cupSpeed]. */
    val speedMin: Float = 200f,
    val speedMax: Float = 380f,
    val laneYTop: Float = 1280f,
    val laneYBottom: Float = 1330f,
)

// ---------------------------------------------------------------------------
// §4.1 Chains
// ---------------------------------------------------------------------------

/** §4.1 direct-contact chain rules driving `ChainTracker`. */
data class ChainConfig(
    /** Chain length that triggers a Sugar Pop. */
    val sugarPopChain: Int = 3,
    /** Sugar Pop radius as a multiple of the lattice spacing `d`. */
    val sugarPopRadiusFactor: Float = 2.0f,
    /** Chain length that awards a ball. */
    val extraBallChain: Int = 5,
    /** The extra ball is capped per launched ball, not per level. */
    val extraBallMaxPerLaunchedBall: Int = 1,
    /** A different-colour contact restarts the chain at 1 in the new colour, it does not zero it. */
    val chainResetValueOnColourChange: Int = 1,
    /** Gems are solid but chain-neutral — smashing one does not break an active candy chain. */
    val gemHitBreaksChain: Boolean = false,
    val onlyDirectContactExtendsChain: Boolean = true,
    /** Candies popped by Sugar Pop or a gem effect score, but do not extend the chain. */
    val effectPoppedCandiesExtendChain: Boolean = false,
    /** The documented exception: a Magnet pull ends in real contact, so it does chain. */
    val magnetPullCountsAsDirectContact: Boolean = true,
)

// ---------------------------------------------------------------------------
// §5 Scoring
// ---------------------------------------------------------------------------

/** §5.1 point values and the §5.3 crown rule. */
data class ScoringConfig(
    /** Direct candy scores 10 times its chain position. */
    val directCandyPointsPerChainPosition: Int = 10,
    /** Capped at the 10th chain position. */
    val directCandyPointsCap: Int = 100,
    /** Flat value for a candy popped by an effect rather than touched. */
    val poppedCandyPoints: Int = 10,
    val gemBrokenPoints: Int = 50,
    val cupCatchPoints: Int = 100,
    /** Sugar Rush: paid per ball still in hand once the objectives are met. */
    val sugarRushPointsPerRemainingBall: Int = 500,
    val dropMultiplier: DropMultiplierDef = DropMultiplierDef(),
    val crowns: CrownConfig = CrownConfig(),
)

/**
 * §5.1/§5.2 drop multiplier.
 *
 * [source] and [formula] are descriptive strings carried through from the JSON so the config stays
 * self-documenting; the engine implements the rule, it does not interpret these.
 */
data class DropMultiplierDef(
    val source: String = "HIGHEST_GEM_MULTIPLIER_IN_DROP",
    /** A drop with no gem still scores, so the multiplier floors at 1 rather than 0. */
    val floor: Int = 1,
    val appliesTo: String = "DROP_SUBTOTAL",
    val formula: String = "dropScore = dropSubtotal * max(highestGemMultiplierInDrop, 1)",
)

/** §5.3 crowns: efficiency, measured in balls left when the objectives complete. */
data class CrownConfig(
    val maxCrowns: Int = 3,
    val source: String = "BALLS_REMAINING",
    val tier1RequiresObjectivesComplete: Boolean = true,
    /** Thresholds are per level (§6.3 `crowns`), so these name the field rather than hold a value. */
    val tier2BallsRemainingFrom: String = "level.crowns[0]",
    val tier3BallsRemainingFrom: String = "level.crowns[1]",
)

// ---------------------------------------------------------------------------
// §4.2 Gems
// ---------------------------------------------------------------------------

/**
 * What smashing a gem does to the board, beyond the score multiplier every gem carries.
 *
 * Modelled as a sealed hierarchy rather than a bag of nullable fields so `GemEffect.kt` (B3) gets
 * an exhaustive `when` with no impossible states — a `PopRadius` cannot accidentally carry a
 * `durationSeconds`. The JSON side stays flat and is narrowed once, in [GemEffectDto.toDomain].
 */
sealed interface GemEffect {

    /** Sweet Gem: pure multiplier, no board effect. */
    data object None : GemEffect

    /** Blast Gem: pops every candy within [radiusFactor] times the lattice spacing `d`. */
    data class PopRadius(val radiusFactor: Float) : GemEffect

    /** Line Gem: horizontal beam popping candies within plus or minus [bandRows] lattice rows. */
    data class PopBand(val bandRows: Float) : GemEffect

    /** Split Gem: spawns [clones] extra balls, optionally mirroring horizontal velocity. */
    data class SplitBall(val clones: Int, val mirrorHorizontalVelocity: Boolean) : GemEffect

    /** Extra Ball Gem: refunds [balls] to the launcher inventory immediately. */
    data class RefundBalls(val balls: Int) : GemEffect

    /**
     * Magnet Gem: for [durationSeconds], candies within [radiusFactor] times `d` drift to the ball.
     * The resulting contact is direct, so it builds chains (§4.1).
     */
    data class Magnet(val durationSeconds: Float, val radiusFactor: Float) : GemEffect

    /** Sugar Storm: pops every candy of the most-populous remaining colour, [maxPerLevel] times. */
    data class PopAllOfLargestColour(val maxPerLevel: Int) : GemEffect
}

/**
 * One of the seven gems (§4.2).
 *
 * [multiplier] and [introLevel] are read from the JSON even though [GemType] also carries them:
 * `ConfigLoaderTest` asserts the two agree, which turns a one-sided edit into a red test instead of
 * a silent gameplay drift.
 */
data class GemDef(
    val type: GemType,
    val sprite: String,
    /** Display name, e.g. "Blast Gem". */
    val name: String,
    /** Display colour name, e.g. "Gold" — for the gem-intro dialog, not for rendering. */
    val colorName: String,
    val multiplier: Int,
    /** First level this gem can appear on. */
    val introLevel: Int,
    val effect: GemEffect,
)

/** §4.2 encoded in Kotlin. Multiplier and intro level come from [GemType] so they cannot drift. */
val DEFAULT_GEMS: List<GemDef> = listOf(
    defaultGem(GemType.SWEET, "Sweet Gem", "Purple", GemEffect.None),
    defaultGem(GemType.BLAST, "Blast Gem", "Gold", GemEffect.PopRadius(2.5f)),
    defaultGem(GemType.LINE, "Line Gem", "Cyan", GemEffect.PopBand(0.5f)),
    defaultGem(GemType.SPLIT, "Split Gem", "Red", GemEffect.SplitBall(1, true)),
    defaultGem(GemType.EXTRA_BALL, "Extra Ball", "Green", GemEffect.RefundBalls(1)),
    defaultGem(GemType.MAGNET, "Magnet Gem", "Blue", GemEffect.Magnet(2.0f, 3.0f)),
    defaultGem(GemType.SUGAR_STORM, "Sugar Storm", "Crimson", GemEffect.PopAllOfLargestColour(1)),
)

private fun defaultGem(
    type: GemType,
    name: String,
    colorName: String,
    effect: GemEffect,
) = GemDef(
    type = type,
    sprite = type.spriteName,
    name = name,
    colorName = colorName,
    multiplier = type.multiplier,
    introLevel = type.introLevel,
    effect = effect,
)

// ---------------------------------------------------------------------------
// §3.4 Layout clearance
// ---------------------------------------------------------------------------

/** What `LayoutBuilder` does when a §3.4 rule is violated. */
enum class ClearanceEnforcement(val configKey: String) {
    /** The rule holds for every legal `d`, so nothing has to be enforced at build time. */
    NONE("NONE"),

    /** Drop the pegs crowding a gem, so a ball can always reach it. */
    REMOVE_PEGS_ADJACENT_TO_GEM("REMOVE_PEGS_ADJACENT_TO_GEM"),

    /** Drop lattice nodes too close to a slanted wall. */
    CLIP_OUTER_BORDER_PEGS("CLIP_OUTER_BORDER_PEGS"),
    ;

    companion object {
        fun fromKey(key: String?): ClearanceEnforcement? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/**
 * One row of the §3.4 clearance table.
 *
 * [minSpacing] and [minPegEdgeToWall] stay nullable — unlike everything else in this file — because
 * `null` is meaningful in the source table: each rule constrains exactly one of the two, and
 * flattening the other to 0 would read as "no constraint satisfied at any spacing".
 */
data class ClearanceRule(
    val id: String,
    /** The condition in the PRD's own notation, carried through for traceability. */
    val condition: String,
    /** Minimum lattice spacing `d` that satisfies this rule, u, or `null` if not spacing-based. */
    val minSpacing: Float?,
    /** Minimum peg-edge-to-wall distance, u, or `null` if not a wall rule. */
    val minPegEdgeToWall: Float?,
    val enforcement: ClearanceEnforcement,
)

/** §3.4 clearance rules and the derived minimums `LayoutBuilder` (B2) enforces. */
data class ClearanceConfig(
    /** The 4 u breathing room added to "a ball must fit" conditions. */
    val ballClearancePadding: Float = 4f,
    val minSpacingBallBetweenPegs: Float = 50.0f,
    val minSpacingCandyInLatticeGap: Float = 50.2f,
    /** 73 u exceeds the 66 u [LatticeDef.spacingMin], which is why gems need peg removal. */
    val minSpacingBallBetweenGemAndPeg: Float = 73.0f,
    val minPegEdgeToWall: Float = 36f,
    val rules: List<ClearanceRule> = DEFAULT_CLEARANCE_RULES,
)

/** §3.4 encoded in Kotlin. */
val DEFAULT_CLEARANCE_RULES: List<ClearanceRule> = listOf(
    ClearanceRule(
        id = "BALL_BETWEEN_PEGS",
        condition = "d - 2 * pegRadius >= 2 * ballRadius + 4",
        minSpacing = 50.0f,
        minPegEdgeToWall = null,
        enforcement = ClearanceEnforcement.NONE,
    ),
    ClearanceRule(
        id = "CANDY_IN_LATTICE_GAP",
        condition = "candyRadius + pegRadius <= d / sqrt(3)",
        minSpacing = 50.2f,
        minPegEdgeToWall = null,
        enforcement = ClearanceEnforcement.NONE,
    ),
    ClearanceRule(
        id = "BALL_BETWEEN_GEM_AND_PEG",
        condition = "d - gemRadius - pegRadius >= 2 * ballRadius + 4",
        minSpacing = 73.0f,
        minPegEdgeToWall = null,
        enforcement = ClearanceEnforcement.REMOVE_PEGS_ADJACENT_TO_GEM,
    ),
    ClearanceRule(
        id = "BALL_BETWEEN_PEG_AND_WALL",
        condition = "pegEdgeToWallDistance >= 36",
        minSpacing = null,
        minPegEdgeToWall = 36f,
        enforcement = ClearanceEnforcement.CLIP_OUTER_BORDER_PEGS,
    ),
)

// ---------------------------------------------------------------------------
// §6.1 Worlds and §6.2 gates
// ---------------------------------------------------------------------------

/** One of the four panorama worlds (§6.1). */
data class WorldDef(
    /** 1-based, matching the PRD's "World 3" phrasing. */
    val index: Int,
    val name: String,
    val levelFrom: Int,
    val levelTo: Int,
    /** Drawable name. Backdrops are `world_1`..`world_4` — a resource name cannot start with a digit. */
    val backdrop: String,
    /** Peg tint as authored, e.g. "#FF4FC8". See [pegTintArgb] for the drawing form. */
    val pegTint: String,
    val pegTintName: String,
    val ballsFrom: Int,
    val ballsTo: Int,
    val spacingFrom: Float,
    val spacingTo: Float,
    val cupSpeed: Float,
    /** §3.3 oscillating rows — Worlds 3 and 4 only. */
    val movingPegRows: Boolean,
) {

    /**
     * [pegTint] as a packed ARGB int, ready for Compose `Color(...)` or a `Paint`.
     *
     * Parsed here, once at construction, rather than via `android.graphics.Color.parseColor`: that
     * is an android.jar stub in JVM unit tests and, with `isReturnDefaultValues` on, would silently
     * return 0 instead of failing. Defaults to opaque white if the hex is unusable.
     */
    val pegTintArgb: Int = parseArgbOrNull(pegTint) ?: OPAQUE_WHITE

    /** Where [levelId] sits in this world, 0f at [levelFrom] and 1f at [levelTo]. */
    internal fun progressAt(levelId: Int): Float {
        val span = levelTo - levelFrom
        if (span <= 0) return 0f
        return ((levelId - levelFrom).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** §6.1 encoded in Kotlin. */
val DEFAULT_WORLDS: List<WorldDef> = listOf(
    WorldDef(
        index = 1,
        name = "Sugar Stage",
        levelFrom = 1,
        levelTo = 10,
        backdrop = "world_1",
        pegTint = "#FF4FC8",
        pegTintName = "Pink",
        ballsFrom = 12,
        ballsTo = 10,
        spacingFrom = 90f,
        spacingTo = 84f,
        cupSpeed = 200f,
        movingPegRows = false,
    ),
    WorldDef(
        index = 2,
        name = "Velvet Swirl",
        levelFrom = 11,
        levelTo = 20,
        backdrop = "world_2",
        pegTint = "#3FE3FF",
        pegTintName = "Cyan",
        ballsFrom = 11,
        ballsTo = 9,
        spacingFrom = 84f,
        spacingTo = 78f,
        cupSpeed = 260f,
        movingPegRows = false,
    ),
    WorldDef(
        index = 3,
        name = "Neon Ribbons",
        levelFrom = 21,
        levelTo = 30,
        backdrop = "world_3",
        pegTint = "#A56BFF",
        pegTintName = "Violet",
        ballsFrom = 10,
        ballsTo = 9,
        spacingFrom = 78f,
        spacingTo = 72f,
        cupSpeed = 320f,
        movingPegRows = true,
    ),
    WorldDef(
        index = 4,
        name = "Golden Thread",
        levelFrom = 31,
        levelTo = 40,
        backdrop = "world_4",
        pegTint = "#FFC23F",
        pegTintName = "Gold",
        ballsFrom = 10,
        ballsTo = 8,
        spacingFrom = 72f,
        spacingTo = 66f,
        cupSpeed = 380f,
        movingPegRows = true,
    ),
)

/**
 * A §6.2 world unlock gate.
 *
 * Both conditions apply: the previous world's last level must be cleared *and* the player must hold
 * [crownsRequired] of the [maxAvailableCrowns] on offer so far.
 */
data class GateDef(
    val world: Int,
    val requiresLevelCleared: Int,
    val crownsRequired: Int,
    val maxAvailableCrowns: Int,
) {
    /** Share of available crowns this gate demands, for the "50% / 58% / 61%" column in §6.2. */
    val completionFraction: Float =
        if (maxAvailableCrowns > 0) crownsRequired.toFloat() / maxAvailableCrowns else 0f
}

/** §6.2 encoded in Kotlin. World 1 is open, so there are three gates, not four. */
val DEFAULT_GATES: List<GateDef> = listOf(
    GateDef(world = 2, requiresLevelCleared = 10, crownsRequired = 15, maxAvailableCrowns = 30),
    GateDef(world = 3, requiresLevelCleared = 20, crownsRequired = 35, maxAvailableCrowns = 60),
    GateDef(world = 4, requiresLevelCleared = 30, crownsRequired = 55, maxAvailableCrowns = 90),
)

// ---------------------------------------------------------------------------
// §7 Candy Jar
// ---------------------------------------------------------------------------

/**
 * One lifetime colour jar and its three unlocks (§7).
 *
 * The `tier1BallSkinSprite` / `defaultBallSkinSprite` keys in the JSON are deliberately not
 * modelled: [BallSkin.spriteName] already owns that mapping, and carrying a second copy here would
 * be one more thing that can disagree.
 */
data class JarDef(
    val color: CandyColor,
    /** Tier 1 reward. Note the Pink jar awards [BallSkin.GOLD], not a pink ball — §7, intentional. */
    val tier1BallSkin: BallSkin,
    val tier2Trail: TrailType,
    /** "B1".."B4", the display code for the Sweet Room. */
    val tier3SweetRoomCode: String,
    /** The same Sweet Room as an id in the single [LevelIds] keyspace, 101..104. */
    val tier3SweetRoomLevelId: Int,
)

/** §7 jar thresholds and rewards. */
data class JarConfig(
    /**
     * Candies needed for tiers 1, 2 and 3. The array is the source of truth; the JSON's
     * `tier1Threshold`..`tier3Threshold` scalars are only read as a fallback when it is absent.
     */
    val tierThresholds: List<Int> = listOf(40, 120, 200),
    /** Candies bank even from a failed level — the jar is a consolation track, not a win reward. */
    val bankCandiesOnFailedLevels: Boolean = true,
    val defaultBallSkin: BallSkin = BallSkin.DEFAULT,
    val defaultTrail: TrailType = TrailType.NONE,
    val jars: List<JarDef> = DEFAULT_JARS,
    /** Sweet Rooms are generous by design (§7). */
    val sweetRoomBalls: Int = 15,
) {
    val tier1Threshold: Int get() = tierThresholds.getOrElse(0) { 40 }
    val tier2Threshold: Int get() = tierThresholds.getOrElse(1) { 120 }
    val tier3Threshold: Int get() = tierThresholds.getOrElse(2) { 200 }

    /** How many of the three tiers [candies] has unlocked, 0..3. */
    fun tierFor(candies: Int): Int = tierThresholds.count { candies >= it }
}

/** §7 encoded in Kotlin. */
val DEFAULT_JARS: List<JarDef> = listOf(
    defaultJar(CandyColor.GREEN, BallSkin.GREEN, TrailType.GREEN, "B1"),
    defaultJar(CandyColor.PURPLE, BallSkin.PURPLE, TrailType.PURPLE, "B2"),
    // Pink's tier 1 is the Gold skin, not a pink one. Straight from the §7 table.
    defaultJar(CandyColor.PINK, BallSkin.GOLD, TrailType.PINK, "B3"),
    defaultJar(CandyColor.BLUE, BallSkin.BLUE, TrailType.BLUE, "B4"),
)

private fun defaultJar(
    color: CandyColor,
    skin: BallSkin,
    trail: TrailType,
    sweetRoom: String,
) = JarDef(
    color = color,
    tier1BallSkin = skin,
    tier2Trail = trail,
    tier3SweetRoomCode = sweetRoom,
    tier3SweetRoomLevelId = LevelIds.fromBonusCode(sweetRoom) ?: LevelIds.BONUS_FIRST,
)

// ---------------------------------------------------------------------------
// §4.1 Candies
// ---------------------------------------------------------------------------

/** One candy colour's presentation data (§4.1). The sprite lives on [CandyColor.spriteName]. */
data class CandyColorDef(val color: CandyColor, val hex: String) {

    /** [hex] as a packed ARGB int; see [WorldDef.pegTintArgb] for why this is parsed by hand. */
    val argb: Int = parseArgbOrNull(hex) ?: OPAQUE_WHITE
}

/** §4.1 candy placement rules and palette. */
data class CandyConfig(
    /** Candies are non-solid: a ball passes through and collects rather than bouncing. */
    val sensor: Boolean = true,
    /** Candies sit at lattice gap centres, so they never overlap a peg. */
    val placedAtLatticeGapCenters: Boolean = true,
    /** Seeded, because §11 requires the same level to lay out identically every run. */
    val seededRandomPlacement: Boolean = true,
    val colors: List<CandyColorDef> = DEFAULT_CANDY_COLORS,
)

/** §4.1 encoded in Kotlin. */
val DEFAULT_CANDY_COLORS: List<CandyColorDef> = listOf(
    CandyColorDef(CandyColor.GREEN, "#3DE84B"),
    CandyColorDef(CandyColor.PURPLE, "#B23CF0"),
    CandyColorDef(CandyColor.PINK, "#FF4FC8"),
    CandyColorDef(CandyColor.BLUE, "#33A0FF"),
)

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private const val OPAQUE_WHITE = -0x1 // 0xFFFFFFFF

/** Linear interpolation; [t] is expected pre-clamped to 0..1. */
private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

/**
 * "#RRGGBB" or "#AARRGGBB" to a packed ARGB int, or `null` if [hex] is not one of those.
 *
 * Hand-rolled on purpose — see [WorldDef.pegTintArgb].
 */
internal fun parseArgbOrNull(hex: String?): Int? {
    val digits = hex?.trim()?.removePrefix("#")?.takeIf { it.all(Char::isLetterOrDigit) }
        ?: return null
    val value = digits.toLongOrNull(radix = 16) ?: return null
    return when (digits.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> value.toInt()
        else -> null
    }
}

// ===========================================================================
// Gson DTOs
// ===========================================================================

/**
 * The literal shape of `assets/config.json`: one nullable field per JSON key, nothing more.
 *
 * These exist only so Gson has a reflection target that cannot produce a non-null Kotlin field
 * holding null; every default is applied in the mappers below, never here. Nothing outside this
 * file should name a type in here.
 *
 * They are nested inside one object rather than sitting at package scope on purpose: `LevelDef.kt`
 * models `levels.json` in this same package and needs its own `Cup`, `Gem` and `World` shapes, and
 * two files racing for the same generic top-level DTO names is a redeclaration waiting to happen.
 */
internal object ConfigJson {

    class Root(
        val configVersion: Int? = null,
        val physics: Physics? = null,
        val board: Board? = null,
        val cup: Cup? = null,
        val chains: Chains? = null,
        val scoring: Scoring? = null,
        val gems: List<Gem?>? = null,
        val clearance: Clearance? = null,
        val worlds: List<World?>? = null,
        val gates: List<Gate?>? = null,
        val jar: Jar? = null,
        val candies: Candies? = null,
    )

    class Physics(
        val ballRadius: Float? = null,
        val pegRadius: Float? = null,
        val candyRadius: Float? = null,
        val gemRadius: Float? = null,
        val gravity: Float? = null,
        val launchSpeed: Float? = null,
        val maxBallSpeed: Float? = null,
        val restitutionPeg: Float? = null,
        val restitutionGem: Float? = null,
        val restitutionWall: Float? = null,
        val tangentialDamping: Float? = null,
        val physicsStepMs: Float? = null,
        val deterministic: Boolean? = null,
        val randomJitter: Float? = null,
        val stuckBall: StuckBall? = null,
        val movingPegs: MovingPegs? = null,
        val antiTunnelling: AntiTunnelling? = null,
    )

    class StuckBall(
        val speedThreshold: Float? = null,
        val dwellSeconds: Float? = null,
        val impulseSpeed: Float? = null,
        val towardAxisX: Float? = null,
        val downward: Boolean? = null,
    )

    class MovingPegs(
        val introducedInWorld: Int? = null,
        val tangentialTransfer: Float? = null,
        val oscillation: String? = null,
        val defaultAmplitude: Float? = null,
        val defaultPeriodSeconds: Float? = null,
    )

    class AntiTunnelling(
        val maxDisplacementPerStep: Float? = null,
        val smallestColliderRadius: Float? = null,
        val sweptCollisionRequired: Boolean? = null,
    )

    class Point(val x: Float? = null, val y: Float? = null)

    class Band(val yTop: Float? = null, val yBottom: Float? = null)

    class Segment(
        val x1: Float? = null,
        val y1: Float? = null,
        val x2: Float? = null,
        val y2: Float? = null,
    )

    class Board(
        val width: Float? = null,
        val height: Float? = null,
        val apex: Point? = null,
        val centerAxisX: Float? = null,
        val baseY: Float? = null,
        val baseWidth: Float? = null,
        val baseOpen: Boolean? = null,
        val launcherZone: Band? = null,
        val latticeBand: Band? = null,
        val cupLane: Band? = null,
        val walls: Walls? = null,
        val lattice: Lattice? = null,
        val launcher: Launcher? = null,
    )

    class Walls(
        val slantDegreesFromVertical: Float? = null,
        val left: Segment? = null,
        val right: Segment? = null,
    )

    class Lattice(
        val type: String? = null,
        val spacingMin: Float? = null,
        val spacingMax: Float? = null,
        val rowSpacingFactor: Float? = null,
        val clipToInterior: Boolean? = null,
    )

    class Launcher(
        val pivot: Point? = null,
        val aimClampDegrees: Float? = null,
        val aimReference: String? = null,
        val aimGuideStopsAtFirstContact: Boolean? = null,
        val aimGuideDotted: Boolean? = null,
        val maxSimultaneousBalls: Int? = null,
    )

    class Cup(
        val cupWidth: Float? = null,
        val catchScore: Int? = null,
        val catchRefundBalls: Int? = null,
        val speedMin: Float? = null,
        val speedMax: Float? = null,
        val laneYTop: Float? = null,
        val laneYBottom: Float? = null,
    )

    class Chains(
        val sugarPopChain: Int? = null,
        val sugarPopRadiusFactor: Float? = null,
        val extraBallChain: Int? = null,
        val extraBallMaxPerLaunchedBall: Int? = null,
        val chainResetValueOnColourChange: Int? = null,
        val gemHitBreaksChain: Boolean? = null,
        val onlyDirectContactExtendsChain: Boolean? = null,
        val effectPoppedCandiesExtendChain: Boolean? = null,
        val magnetPullCountsAsDirectContact: Boolean? = null,
    )

    class Scoring(
        val directCandyPointsPerChainPosition: Int? = null,
        val directCandyPointsCap: Int? = null,
        val poppedCandyPoints: Int? = null,
        val gemBrokenPoints: Int? = null,
        val cupCatchPoints: Int? = null,
        val sugarRushPointsPerRemainingBall: Int? = null,
        val dropMultiplier: DropMultiplier? = null,
        val crowns: Crowns? = null,
    )

    class DropMultiplier(
        val source: String? = null,
        val floor: Int? = null,
        val appliesTo: String? = null,
        val formula: String? = null,
    )

    class Crowns(
        val maxCrowns: Int? = null,
        val source: String? = null,
        val tier1RequiresObjectivesComplete: Boolean? = null,
        val tier2BallsRemainingFrom: String? = null,
        val tier3BallsRemainingFrom: String? = null,
    )

    class Gem(
        val key: String? = null,
        val sprite: String? = null,
        val name: String? = null,
        val colorName: String? = null,
        val multiplier: Int? = null,
        val introLevel: Int? = null,
        val effect: Effect? = null,
    )

    /**
     * The flat shape of `gems[].effect`.
     *
     * Every effect's payload is unioned into one nullable-field class so Gson's stock reflective
     * adapter handles the polymorphism for free — no `JsonDeserializer`, no `TypeAdapter`, no
     * `RuntimeTypeAdapterFactory`. The union is narrowed exactly once, in [Effect.toDomain], where
     * `type` picks the branch and the irrelevant fields are simply never read.
     */
    class Effect(
        val type: String? = null,
        val radiusFactor: Float? = null,
        val bandRows: Float? = null,
        val clones: Int? = null,
        val mirrorHorizontalVelocity: Boolean? = null,
        val balls: Int? = null,
        val durationSeconds: Float? = null,
        val maxPerLevel: Int? = null,
    )

    class Clearance(
        val ballClearancePadding: Float? = null,
        val minSpacingBallBetweenPegs: Float? = null,
        val minSpacingCandyInLatticeGap: Float? = null,
        val minSpacingBallBetweenGemAndPeg: Float? = null,
        val minPegEdgeToWall: Float? = null,
        val rules: List<Rule?>? = null,
    )

    class Rule(
        val id: String? = null,
        val condition: String? = null,
        val minSpacing: Float? = null,
        val minPegEdgeToWall: Float? = null,
        val enforcement: String? = null,
    )

    class World(
        val index: Int? = null,
        val name: String? = null,
        val levelFrom: Int? = null,
        val levelTo: Int? = null,
        val backdrop: String? = null,
        val pegTint: String? = null,
        val pegTintName: String? = null,
        val ballsFrom: Int? = null,
        val ballsTo: Int? = null,
        val spacingFrom: Float? = null,
        val spacingTo: Float? = null,
        val cupSpeed: Float? = null,
        val movingPegRows: Boolean? = null,
    )

    class Gate(
        val world: Int? = null,
        val requiresLevelCleared: Int? = null,
        val crownsRequired: Int? = null,
        val maxAvailableCrowns: Int? = null,
    )

    class Jar(
        val tier1Threshold: Int? = null,
        val tier2Threshold: Int? = null,
        val tier3Threshold: Int? = null,
        val tierThresholds: List<Int?>? = null,
        val bankCandiesOnFailedLevels: Boolean? = null,
        val defaultBallSkin: String? = null,
        val defaultTrail: String? = null,
        val jars: List<JarEntry?>? = null,
        val sweetRoomBalls: Int? = null,
    )

    class JarEntry(
        val color: String? = null,
        val tier1BallSkin: String? = null,
        val tier2Trail: String? = null,
        val tier3SweetRoom: String? = null,
    )

    class Candies(
        val sensor: Boolean? = null,
        val placedAtLatticeGapCenters: Boolean? = null,
        val seededRandomPlacement: Boolean? = null,
        val colors: List<CandyEntry?>? = null,
    )

    class CandyEntry(
        val key: String? = null,
        val sprite: String? = null,
        val hex: String? = null,
    )
}

// ===========================================================================
// DTO -> domain mappers
//
// Uniform shape: start from the defaults, then overwrite each field only where the JSON supplied
// something usable. A null receiver means the whole object was absent, which is the same outcome
// as every field being absent.
// ===========================================================================

internal fun ConfigJson.Root?.toDomain(): GameConfig {
    val d = GameConfig.DEFAULTS
    if (this == null) return d
    return d.copy(
        configVersion = configVersion ?: d.configVersion,
        physics = physics.toDomain(),
        board = board.toDomain(),
        cup = cup.toDomain(),
        chains = chains.toDomain(),
        scoring = scoring.toDomain(),
        gems = gems.toGemDomain(),
        clearance = clearance.toDomain(),
        worlds = worlds.toWorldDomain(),
        gates = gates.toGateDomain(),
        jar = jar.toDomain(),
        candies = candies.toDomain(),
    )
}

private fun ConfigJson.Physics?.toDomain(): PhysicsConfig {
    val d = PhysicsConfig()
    if (this == null) return d
    return d.copy(
        ballRadius = ballRadius ?: d.ballRadius,
        pegRadius = pegRadius ?: d.pegRadius,
        candyRadius = candyRadius ?: d.candyRadius,
        gemRadius = gemRadius ?: d.gemRadius,
        gravity = gravity ?: d.gravity,
        launchSpeed = launchSpeed ?: d.launchSpeed,
        maxBallSpeed = maxBallSpeed ?: d.maxBallSpeed,
        restitutionPeg = restitutionPeg ?: d.restitutionPeg,
        restitutionGem = restitutionGem ?: d.restitutionGem,
        restitutionWall = restitutionWall ?: d.restitutionWall,
        tangentialDamping = tangentialDamping ?: d.tangentialDamping,
        physicsStepMs = physicsStepMs ?: d.physicsStepMs,
        deterministic = deterministic ?: d.deterministic,
        randomJitter = randomJitter ?: d.randomJitter,
        stuckBall = stuckBall.toDomain(),
        movingPegs = movingPegs.toDomain(),
        antiTunnelling = antiTunnelling.toDomain(),
    )
}

private fun ConfigJson.StuckBall?.toDomain(): StuckBallConfig {
    val d = StuckBallConfig()
    if (this == null) return d
    return d.copy(
        speedThreshold = speedThreshold ?: d.speedThreshold,
        dwellSeconds = dwellSeconds ?: d.dwellSeconds,
        impulseSpeed = impulseSpeed ?: d.impulseSpeed,
        towardAxisX = towardAxisX ?: d.towardAxisX,
        downward = downward ?: d.downward,
    )
}

private fun ConfigJson.MovingPegs?.toDomain(): MovingPegConfig {
    val d = MovingPegConfig()
    if (this == null) return d
    return d.copy(
        introducedInWorld = introducedInWorld ?: d.introducedInWorld,
        tangentialTransfer = tangentialTransfer ?: d.tangentialTransfer,
        oscillation = oscillation ?: d.oscillation,
        defaultAmplitude = defaultAmplitude ?: d.defaultAmplitude,
        defaultPeriodSeconds = defaultPeriodSeconds ?: d.defaultPeriodSeconds,
    )
}

private fun ConfigJson.AntiTunnelling?.toDomain(): AntiTunnellingConfig {
    val d = AntiTunnellingConfig()
    if (this == null) return d
    return d.copy(
        maxDisplacementPerStep = maxDisplacementPerStep ?: d.maxDisplacementPerStep,
        smallestColliderRadius = smallestColliderRadius ?: d.smallestColliderRadius,
        sweptCollisionRequired = sweptCollisionRequired ?: d.sweptCollisionRequired,
    )
}

private fun ConfigJson.Point?.toDomain(d: BoardPoint): BoardPoint =
    if (this == null) d else BoardPoint(x = x ?: d.x, y = y ?: d.y)

private fun ConfigJson.Band?.toDomain(d: YBand): YBand =
    if (this == null) d else YBand(yTop = yTop ?: d.yTop, yBottom = yBottom ?: d.yBottom)

private fun ConfigJson.Segment?.toDomain(d: WallLine): WallLine =
    if (this == null) {
        d
    } else {
        WallLine(x1 = x1 ?: d.x1, y1 = y1 ?: d.y1, x2 = x2 ?: d.x2, y2 = y2 ?: d.y2)
    }

private fun ConfigJson.Board?.toDomain(): BoardConfig {
    val d = BoardConfig()
    if (this == null) return d
    return d.copy(
        width = width ?: d.width,
        height = height ?: d.height,
        apex = apex.toDomain(d.apex),
        centerAxisX = centerAxisX ?: d.centerAxisX,
        baseY = baseY ?: d.baseY,
        baseWidth = baseWidth ?: d.baseWidth,
        baseOpen = baseOpen ?: d.baseOpen,
        launcherZone = launcherZone.toDomain(d.launcherZone),
        latticeBand = latticeBand.toDomain(d.latticeBand),
        cupLane = cupLane.toDomain(d.cupLane),
        walls = walls.toDomain(),
        lattice = lattice.toDomain(),
        launcher = launcher.toDomain(),
    )
}

private fun ConfigJson.Walls?.toDomain(): WallsConfig {
    val d = WallsConfig()
    if (this == null) return d
    return d.copy(
        slantDegreesFromVertical = slantDegreesFromVertical ?: d.slantDegreesFromVertical,
        left = left.toDomain(d.left),
        right = right.toDomain(d.right),
    )
}

private fun ConfigJson.Lattice?.toDomain(): LatticeDef {
    val d = LatticeDef()
    if (this == null) return d
    return d.copy(
        type = type ?: d.type,
        spacingMin = spacingMin ?: d.spacingMin,
        spacingMax = spacingMax ?: d.spacingMax,
        rowSpacingFactor = rowSpacingFactor ?: d.rowSpacingFactor,
        clipToInterior = clipToInterior ?: d.clipToInterior,
    )
}

private fun ConfigJson.Launcher?.toDomain(): LauncherDef {
    val d = LauncherDef()
    if (this == null) return d
    return d.copy(
        pivot = pivot.toDomain(d.pivot),
        aimClampDegrees = aimClampDegrees ?: d.aimClampDegrees,
        aimReference = aimReference ?: d.aimReference,
        aimGuideStopsAtFirstContact = aimGuideStopsAtFirstContact ?: d.aimGuideStopsAtFirstContact,
        aimGuideDotted = aimGuideDotted ?: d.aimGuideDotted,
        maxSimultaneousBalls = maxSimultaneousBalls ?: d.maxSimultaneousBalls,
    )
}

private fun ConfigJson.Cup?.toDomain(): CupConfig {
    val d = CupConfig()
    if (this == null) return d
    return d.copy(
        cupWidth = cupWidth ?: d.cupWidth,
        catchScore = catchScore ?: d.catchScore,
        catchRefundBalls = catchRefundBalls ?: d.catchRefundBalls,
        speedMin = speedMin ?: d.speedMin,
        speedMax = speedMax ?: d.speedMax,
        laneYTop = laneYTop ?: d.laneYTop,
        laneYBottom = laneYBottom ?: d.laneYBottom,
    )
}

private fun ConfigJson.Chains?.toDomain(): ChainConfig {
    val d = ChainConfig()
    if (this == null) return d
    return d.copy(
        sugarPopChain = sugarPopChain ?: d.sugarPopChain,
        sugarPopRadiusFactor = sugarPopRadiusFactor ?: d.sugarPopRadiusFactor,
        extraBallChain = extraBallChain ?: d.extraBallChain,
        extraBallMaxPerLaunchedBall = extraBallMaxPerLaunchedBall ?: d.extraBallMaxPerLaunchedBall,
        chainResetValueOnColourChange =
            chainResetValueOnColourChange ?: d.chainResetValueOnColourChange,
        gemHitBreaksChain = gemHitBreaksChain ?: d.gemHitBreaksChain,
        onlyDirectContactExtendsChain =
            onlyDirectContactExtendsChain ?: d.onlyDirectContactExtendsChain,
        effectPoppedCandiesExtendChain =
            effectPoppedCandiesExtendChain ?: d.effectPoppedCandiesExtendChain,
        magnetPullCountsAsDirectContact =
            magnetPullCountsAsDirectContact ?: d.magnetPullCountsAsDirectContact,
    )
}

private fun ConfigJson.Scoring?.toDomain(): ScoringConfig {
    val d = ScoringConfig()
    if (this == null) return d
    return d.copy(
        directCandyPointsPerChainPosition =
            directCandyPointsPerChainPosition ?: d.directCandyPointsPerChainPosition,
        directCandyPointsCap = directCandyPointsCap ?: d.directCandyPointsCap,
        poppedCandyPoints = poppedCandyPoints ?: d.poppedCandyPoints,
        gemBrokenPoints = gemBrokenPoints ?: d.gemBrokenPoints,
        cupCatchPoints = cupCatchPoints ?: d.cupCatchPoints,
        sugarRushPointsPerRemainingBall =
            sugarRushPointsPerRemainingBall ?: d.sugarRushPointsPerRemainingBall,
        dropMultiplier = dropMultiplier.toDomain(),
        crowns = crowns.toDomain(),
    )
}

private fun ConfigJson.DropMultiplier?.toDomain(): DropMultiplierDef {
    val d = DropMultiplierDef()
    if (this == null) return d
    return d.copy(
        source = source ?: d.source,
        floor = floor ?: d.floor,
        appliesTo = appliesTo ?: d.appliesTo,
        formula = formula ?: d.formula,
    )
}

private fun ConfigJson.Crowns?.toDomain(): CrownConfig {
    val d = CrownConfig()
    if (this == null) return d
    return d.copy(
        maxCrowns = maxCrowns ?: d.maxCrowns,
        source = source ?: d.source,
        tier1RequiresObjectivesComplete =
            tier1RequiresObjectivesComplete ?: d.tier1RequiresObjectivesComplete,
        tier2BallsRemainingFrom = tier2BallsRemainingFrom ?: d.tier2BallsRemainingFrom,
        tier3BallsRemainingFrom = tier3BallsRemainingFrom ?: d.tier3BallsRemainingFrom,
    )
}

/**
 * Entries whose `key` does not name a [GemType] are dropped rather than guessed at, and an empty
 * result falls back to the full §4.2 set — a config that knows about no gems is not playable.
 */
private fun List<ConfigJson.Gem?>?.toGemDomain(): List<GemDef> {
    val mapped = this.orEmpty().mapNotNull { it.toDomainOrNull() }
    return mapped.ifEmpty { DEFAULT_GEMS }
}

private fun ConfigJson.Gem?.toDomainOrNull(): GemDef? {
    val type = GemType.fromKey(this?.key) ?: return null
    val d = DEFAULT_GEMS.firstOrNull { it.type == type }
    return GemDef(
        type = type,
        sprite = this?.sprite ?: type.spriteName,
        name = this?.name ?: d?.name ?: type.configKey,
        colorName = this?.colorName ?: d?.colorName.orEmpty(),
        multiplier = this?.multiplier ?: type.multiplier,
        introLevel = this?.introLevel ?: type.introLevel,
        effect = this?.effect?.toDomain() ?: d?.effect ?: GemEffect.None,
    )
}

/**
 * The one place `gems[].effect` stops being polymorphic JSON and becomes a typed [GemEffect].
 *
 * An unknown `type` degrades to [GemEffect.None] rather than throwing: the gem keeps its score
 * multiplier, which is the part that matters, and simply has no board effect.
 */
private fun ConfigJson.Effect.toDomain(): GemEffect = when (type?.uppercase()) {
    "NONE", null -> GemEffect.None
    "POP_RADIUS" -> GemEffect.PopRadius(radiusFactor ?: 2.5f)
    "POP_BAND" -> GemEffect.PopBand(bandRows ?: 0.5f)
    "SPLIT_BALL" -> GemEffect.SplitBall(
        clones = clones ?: 1,
        mirrorHorizontalVelocity = mirrorHorizontalVelocity ?: true,
    )
    "REFUND_BALLS" -> GemEffect.RefundBalls(balls ?: 1)
    "MAGNET" -> GemEffect.Magnet(
        durationSeconds = durationSeconds ?: 2.0f,
        radiusFactor = radiusFactor ?: 3.0f,
    )
    "POP_ALL_OF_LARGEST_COLOUR" -> GemEffect.PopAllOfLargestColour(maxPerLevel = maxPerLevel ?: 1)
    else -> GemEffect.None
}

private fun ConfigJson.Clearance?.toDomain(): ClearanceConfig {
    val d = ClearanceConfig()
    if (this == null) return d
    val mapped = rules.orEmpty().mapNotNull { it.toDomainOrNull() }
    return d.copy(
        ballClearancePadding = ballClearancePadding ?: d.ballClearancePadding,
        minSpacingBallBetweenPegs = minSpacingBallBetweenPegs ?: d.minSpacingBallBetweenPegs,
        minSpacingCandyInLatticeGap =
            minSpacingCandyInLatticeGap ?: d.minSpacingCandyInLatticeGap,
        minSpacingBallBetweenGemAndPeg =
            minSpacingBallBetweenGemAndPeg ?: d.minSpacingBallBetweenGemAndPeg,
        minPegEdgeToWall = minPegEdgeToWall ?: d.minPegEdgeToWall,
        rules = mapped.ifEmpty { d.rules },
    )
}

/** An unidentifiable rule is dropped — a rule with no id is not actionable by `LayoutBuilder`. */
private fun ConfigJson.Rule?.toDomainOrNull(): ClearanceRule? {
    val id = this?.id?.takeIf { it.isNotBlank() } ?: return null
    return ClearanceRule(
        id = id,
        condition = condition.orEmpty(),
        minSpacing = minSpacing,
        minPegEdgeToWall = minPegEdgeToWall,
        // An unrecognised enforcement means "do nothing", the safe outcome: the board still builds.
        enforcement = ClearanceEnforcement.fromKey(enforcement) ?: ClearanceEnforcement.NONE,
    )
}

/**
 * Worlds are matched to the §6.1 defaults by [WorldDef.index] so a partially-specified world (say,
 * one that only re-tints its pegs) inherits the rest of its row instead of collapsing to zeroes.
 */
private fun List<ConfigJson.World?>?.toWorldDomain(): List<WorldDef> {
    val mapped = this.orEmpty().mapNotNull { it.toDomainOrNull() }
    return mapped.ifEmpty { DEFAULT_WORLDS }
}

private fun ConfigJson.World?.toDomainOrNull(): WorldDef? {
    val index = this?.index ?: return null
    val d = DEFAULT_WORLDS.firstOrNull { it.index == index } ?: DEFAULT_WORLDS.first()
    return WorldDef(
        index = index,
        name = name ?: d.name,
        levelFrom = levelFrom ?: d.levelFrom,
        levelTo = levelTo ?: d.levelTo,
        backdrop = backdrop ?: d.backdrop,
        pegTint = pegTint ?: d.pegTint,
        pegTintName = pegTintName ?: d.pegTintName,
        ballsFrom = ballsFrom ?: d.ballsFrom,
        ballsTo = ballsTo ?: d.ballsTo,
        spacingFrom = spacingFrom ?: d.spacingFrom,
        spacingTo = spacingTo ?: d.spacingTo,
        cupSpeed = cupSpeed ?: d.cupSpeed,
        movingPegRows = movingPegRows ?: d.movingPegRows,
    )
}

private fun List<ConfigJson.Gate?>?.toGateDomain(): List<GateDef> {
    val mapped = this.orEmpty().mapNotNull { it.toDomainOrNull() }
    return mapped.ifEmpty { DEFAULT_GATES }
}

private fun ConfigJson.Gate?.toDomainOrNull(): GateDef? {
    val world = this?.world ?: return null
    val d = DEFAULT_GATES.firstOrNull { it.world == world }
    return GateDef(
        world = world,
        requiresLevelCleared = requiresLevelCleared ?: d?.requiresLevelCleared ?: 0,
        crownsRequired = crownsRequired ?: d?.crownsRequired ?: 0,
        maxAvailableCrowns = maxAvailableCrowns ?: d?.maxAvailableCrowns ?: 0,
    )
}

private fun ConfigJson.Jar?.toDomain(): JarConfig {
    val d = JarConfig()
    if (this == null) return d
    // The array wins; the scalar keys are the fallback, and the §7 defaults the fallback's fallback.
    val thresholds = tierThresholds
        ?.filterNotNull()
        ?.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(tier1Threshold, tier2Threshold, tier3Threshold).takeIf { it.size == 3 }
        ?: d.tierThresholds
    val mappedJars = jars.orEmpty().mapNotNull { it.toDomainOrNull() }
    return d.copy(
        tierThresholds = thresholds,
        bankCandiesOnFailedLevels = bankCandiesOnFailedLevels ?: d.bankCandiesOnFailedLevels,
        defaultBallSkin = BallSkin.fromKey(defaultBallSkin) ?: d.defaultBallSkin,
        defaultTrail = TrailType.fromKey(defaultTrail) ?: d.defaultTrail,
        jars = mappedJars.ifEmpty { d.jars },
        sweetRoomBalls = sweetRoomBalls ?: d.sweetRoomBalls,
    )
}

private fun ConfigJson.JarEntry?.toDomainOrNull(): JarDef? {
    val color = CandyColor.fromKey(this?.color) ?: return null
    val d = DEFAULT_JARS.firstOrNull { it.color == color }
    val code = this?.tier3SweetRoom ?: d?.tier3SweetRoomCode.orEmpty()
    return JarDef(
        color = color,
        // No silent colour-matching fallback here: Pink's tier 1 is GOLD, so "the skin named after
        // the jar" is the wrong guess for one of the four.
        tier1BallSkin = BallSkin.fromKey(this?.tier1BallSkin)
            ?: d?.tier1BallSkin
            ?: BallSkin.DEFAULT,
        tier2Trail = TrailType.fromKey(this?.tier2Trail) ?: d?.tier2Trail ?: TrailType.NONE,
        tier3SweetRoomCode = code,
        tier3SweetRoomLevelId = LevelIds.fromBonusCode(code)
            ?: d?.tier3SweetRoomLevelId
            ?: LevelIds.BONUS_FIRST,
    )
}

private fun ConfigJson.Candies?.toDomain(): CandyConfig {
    val d = CandyConfig()
    if (this == null) return d
    val mapped = colors.orEmpty().mapNotNull { it.toDomainOrNull() }
    return d.copy(
        sensor = sensor ?: d.sensor,
        placedAtLatticeGapCenters = placedAtLatticeGapCenters ?: d.placedAtLatticeGapCenters,
        seededRandomPlacement = seededRandomPlacement ?: d.seededRandomPlacement,
        colors = mapped.ifEmpty { d.colors },
    )
}

private fun ConfigJson.CandyEntry?.toDomainOrNull(): CandyColorDef? {
    val color = CandyColor.fromKey(this?.key) ?: return null
    val d = DEFAULT_CANDY_COLORS.firstOrNull { it.color == color }
    return CandyColorDef(color = color, hex = this?.hex ?: d?.hex.orEmpty())
}
