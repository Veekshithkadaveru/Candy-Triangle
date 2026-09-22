package app.krafted.candytriangle.engine

import app.krafted.candytriangle.data.AssetSource
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.level.BoardConfig
import app.krafted.candytriangle.level.BoardPoint
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LauncherDef
import app.krafted.candytriangle.level.MovingPegConfig
import app.krafted.candytriangle.level.PhysicsConfig
import app.krafted.candytriangle.level.StuckBallConfig
import app.krafted.candytriangle.level.YBand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * [PhysicsParams]: the §3.2 table, the resolution of `config.json` into engine constants, every
 * sanitisation rule, and the launch-velocity maths.
 *
 * Pure JVM. `android.util.Log` is a silent no-op here (`unitTests.isReturnDefaultValues`), so the
 * sanitising branches really do run their warnings.
 */
class PhysicsParamsTest {

    // -- the §3.2 defaults ---------------------------------------------------------------------

    /**
     * Drift guard.
     *
     * [PhysicsParams]'s constructor defaults duplicate `GameConfig.DEFAULTS` on purpose, so an
     * engine test can write `PhysicsParams(gravity = 0f)` without building a config. That
     * duplication is only safe while resolving the config's defaults gives exactly the
     * constructor's: a retune on one side only — or a sanitisation rule that bites a legal §3.2
     * value — would otherwise leave the engine tests and the shipped game quietly simulating
     * different physics. Data-class equality compares every constructor field bit for bit
     * (`Float.compare`), so this is an exact comparison, not a tolerance.
     */
    @Test
    fun constructorDefaultsAreExactlyTheResolvedConfigDefaults() {
        assertEquals(
            "PhysicsParams() and PhysicsParams.from(GameConfig.DEFAULTS) have drifted apart",
            PhysicsParams(),
            PhysicsParams.from(GameConfig.DEFAULTS),
        )
        assertEquals("DEFAULT is from(GameConfig.DEFAULTS)", PhysicsParams(), PhysicsParams.DEFAULT)
    }

    @Test
    fun defaultsMatchTheSection32Table() {
        val p = PhysicsParams.DEFAULT

        assertEquals("240 Hz from physicsStepMs 4.1667", 240, p.stepsPerSecond)
        assertF("gravity", 1400f, p.gravity)
        assertF("launchSpeed", 900f, p.launchSpeed)
        assertF("maxBallSpeed", 1600f, p.maxBallSpeed)
        assertF("ballRadius", 16f, p.ballRadius)
        assertF("pegRadius", 7f, p.pegRadius)
        assertF("gemRadius", 30f, p.gemRadius)
        assertF("restitutionPeg", 0.60f, p.restitutionPeg)
        assertF("restitutionGem", 0.65f, p.restitutionGem)
        assertF("restitutionWall", 0.50f, p.restitutionWall)
        assertF("tangentialDamping", 0.98f, p.tangentialDamping)

        // §3.3 special rules.
        assertF("stuck-ball speed threshold", 30f, p.stuckSpeedThreshold)
        assertEquals("stuck-ball dwell: 1.0 s at 240 Hz", 240, p.stuckDwellSteps)
        assertF("stuck-ball impulse", 120f, p.stuckImpulseSpeed)
        assertF("stuck-ball axis", 500f, p.stuckAxisX)
        assertTrue("the rescue also pushes downward", p.stuckDownward)
        assertF("moving-peg velocity transfer", 0.50f, p.movingPegTransfer)

        // §2 / §3.1 launcher and board.
        assertEquals("aim clamp is 70 degrees", 70.0, degrees(p.aimClampRadians), 1e-5)
        assertF("spawn on the axis", 500f, p.spawnX)
        assertF("spawn mid launcher zone (0-150)", 75f, p.spawnY)
        assertF("exit: cup lane bottom 1330 + ball radius 16", 1346f, p.exitY)
    }

    @Test
    fun theFixedTimestepIsOneTwoHundredFortiethOfASecond() {
        val p = PhysicsParams.DEFAULT
        assertEquals("dt is the float quotient 1 / 240", 1f / 240, p.dt, 0f)
        assertEquals("dt is 1/240 s", 1.0 / 240, p.dt.toDouble(), 1e-9)
    }

    /**
     * §3.3's whole case against swept collision: at terminal speed a step moves 1600 / 240 =
     * 6.67 u, strictly less than the smallest solid radius — the 7 u peg.
     */
    @Test
    fun maxStepDisplacementStaysUnderTheSmallestSolidRadius() {
        val p = PhysicsParams.DEFAULT
        assertEquals("1600 / 240", 1600.0 / 240, p.maxStepDisplacement.toDouble(), 1e-4)
        assertTrue("6.67 u must be < 7 u", p.maxStepDisplacement < 7f)
        assertTrue(
            "must stay under every solid radius, the ball's included",
            p.maxStepDisplacement < minOf(p.pegRadius, p.gemRadius, p.ballRadius),
        )
    }

    // -- the shipped config.json ---------------------------------------------------------------

    /**
     * The file the app actually ships, read off disk the way `ConfigLoaderTest` does, must resolve
     * to exactly the §3.2 constructor defaults — so every engine test built on `PhysicsParams()` is
     * simulating the shipped game.
     */
    @Test
    fun theShippedConfigResolvesToExactlyTheDefaults() = runTest {
        val onDisk = File("src/main/assets/config.json")
        assertTrue(
            "config.json not found; unit-test working directory is ${File("").absolutePath}",
            onDisk.exists(),
        )
        val config = ConfigLoader(AssetSource { path -> File("src/main/assets/$path").readText() })
            .config()
        // ConfigLoader hands back the DEFAULTS instance itself when it cannot parse; a parsed
        // file is always a fresh copy. Without this the comparison below would pass vacuously.
        assertNotSame("config.json failed to parse; loader fell back", GameConfig.DEFAULTS, config)

        assertEquals(PhysicsParams(), PhysicsParams.from(config))
    }

    // -- sanitisation: fallbacks ---------------------------------------------------------------

    /** Every magnitude and coefficient unusable at once: all of them fall back, nothing throws. */
    @Test
    fun anEntirelyNonFiniteConfigResolvesToTheDefaults() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val config = GameConfig.DEFAULTS.copy(
                physics = PhysicsConfig(
                    ballRadius = bad,
                    pegRadius = bad,
                    gemRadius = bad,
                    gravity = bad,
                    launchSpeed = bad,
                    maxBallSpeed = bad,
                    restitutionPeg = bad,
                    restitutionGem = bad,
                    restitutionWall = bad,
                    tangentialDamping = bad,
                    physicsStepMs = bad,
                    stuckBall = StuckBallConfig(
                        speedThreshold = bad,
                        dwellSeconds = bad,
                        impulseSpeed = bad,
                        towardAxisX = bad,
                    ),
                    movingPegs = MovingPegConfig(tangentialTransfer = bad),
                ),
                board = BoardConfig(
                    baseY = bad,
                    launcherZone = YBand(bad, bad),
                    cupLane = YBand(bad, bad),
                    launcher = LauncherDef(pivot = BoardPoint(bad, bad), aimClampDegrees = bad),
                ),
            )
            assertEquals("every field set to $bad", PhysicsParams(), PhysicsParams.from(config))
        }
    }

    @Test
    fun nonPositiveMagnitudesFallBackToTheirDefaults() {
        for (bad in listOf(0f, -0f, -1f, -1400f)) {
            val p = resolve(
                PhysicsConfig(
                    ballRadius = bad,
                    pegRadius = bad,
                    gemRadius = bad,
                    gravity = bad,
                    launchSpeed = bad,
                    maxBallSpeed = bad,
                    stuckBall = StuckBallConfig(
                        speedThreshold = bad,
                        dwellSeconds = bad,
                        impulseSpeed = bad,
                    ),
                ),
            )
            assertF("ballRadius $bad", 16f, p.ballRadius)
            assertF("pegRadius $bad", 7f, p.pegRadius)
            assertF("gemRadius $bad", 30f, p.gemRadius)
            assertF("gravity $bad: zero gravity would never end a drop", 1400f, p.gravity)
            assertF("launchSpeed $bad", 900f, p.launchSpeed)
            assertF("maxBallSpeed $bad", 1600f, p.maxBallSpeed)
            assertF("stuck threshold $bad", 30f, p.stuckSpeedThreshold)
            assertEquals("stuck dwell $bad s -> 1 s", 240, p.stuckDwellSteps)
            assertF("stuck impulse $bad", 120f, p.stuckImpulseSpeed)
        }
    }

    @Test
    fun physicsStepMsResolvesToAWholeStepRateWithinTheSaneBand() {
        assertEquals("§3.2's 4.1667 ms", 240, stepsPerSecondFor(4.1667f))
        assertEquals("exactly 1000/240 ms", 240, stepsPerSecondFor(1000f / 240f))
        assertEquals("8.3333 ms", 120, stepsPerSecondFor(8.3333f))
        assertEquals("16.6667 ms", 60, stepsPerSecondFor(16.6667f))
        assertEquals("zero falls back to 240", 240, stepsPerSecondFor(0f))
        assertEquals("negative falls back to 240", 240, stepsPerSecondFor(-4.1667f))
        assertEquals("NaN falls back to 240", 240, stepsPerSecondFor(Float.NaN))
        assertEquals(
            "1 s steps are held at the floor",
            PhysicsParams.MIN_STEPS_PER_SECOND,
            stepsPerSecondFor(1000f),
        )
        assertEquals(
            "0.01 ms steps are held at the ceiling",
            PhysicsParams.MAX_STEPS_PER_SECOND,
            stepsPerSecondFor(0.01f),
        )
        assertEquals(
            "the smallest positive float is held at the ceiling, not overflowed",
            PhysicsParams.MAX_STEPS_PER_SECOND,
            stepsPerSecondFor(Float.MIN_VALUE),
        )
    }

    @Test
    fun theStuckDwellIsSecondsTimesTheStepRateAndAtLeastOneStep() {
        assertEquals("0.5 s at 240 Hz", 120, dwellStepsFor(0.5f))
        assertEquals(
            "1 s at 120 Hz",
            120,
            resolve(PhysicsConfig(physicsStepMs = 8.3333f)).stuckDwellSteps,
        )
        assertEquals("a dwell shorter than a step still waits one step", 1, dwellStepsFor(0.0001f))
        assertEquals(
            "an absurd dwell saturates instead of overflowing",
            Int.MAX_VALUE,
            dwellStepsFor(Float.MAX_VALUE),
        )
    }

    @Test
    fun theStuckRuleCarriesItsAxisAndDirectionThrough() {
        val p = resolve(
            PhysicsConfig(stuckBall = StuckBallConfig(towardAxisX = 420f, downward = false)),
        )
        assertF("towardAxisX", 420f, p.stuckAxisX)
        assertFalse("downward", p.stuckDownward)
    }

    // -- sanitisation: coefficients ------------------------------------------------------------

    @Test
    fun coefficientsAreCoercedIntoZeroToOne() {
        val high = resolve(
            PhysicsConfig(
                restitutionPeg = 1.7f,
                restitutionGem = 2f,
                restitutionWall = 1.0001f,
                tangentialDamping = 1.5f,
                movingPegs = MovingPegConfig(tangentialTransfer = 3f),
            ),
        )
        assertF("restitutionPeg 1.7 -> 1", 1f, high.restitutionPeg)
        assertF("restitutionGem 2 -> 1", 1f, high.restitutionGem)
        assertF("restitutionWall 1.0001 -> 1", 1f, high.restitutionWall)
        assertF("tangentialDamping 1.5 -> 1", 1f, high.tangentialDamping)
        assertF("movingPegTransfer 3 -> 1", 1f, high.movingPegTransfer)

        val low = resolve(
            PhysicsConfig(
                restitutionPeg = -0.3f,
                restitutionGem = -1f,
                restitutionWall = -100f,
                tangentialDamping = -0.98f,
                movingPegs = MovingPegConfig(tangentialTransfer = -0.5f),
            ),
        )
        assertF("restitutionPeg -0.3 -> 0", 0f, low.restitutionPeg)
        assertF("restitutionGem -1 -> 0", 0f, low.restitutionGem)
        assertF("restitutionWall -100 -> 0", 0f, low.restitutionWall)
        assertF("tangentialDamping -0.98 -> 0", 0f, low.tangentialDamping)
        assertF("movingPegTransfer -0.5 -> 0", 0f, low.movingPegTransfer)
    }

    /** Unlike a magnitude, a coefficient of exactly 0 is a real tuning (a dead bounce): kept. */
    @Test
    fun zeroAndOneAreLegalCoefficients() {
        val p = resolve(
            PhysicsConfig(
                restitutionPeg = 0f,
                restitutionWall = 1f,
                tangentialDamping = 0f,
                movingPegs = MovingPegConfig(tangentialTransfer = 0f),
            ),
        )
        assertF("restitutionPeg 0 is kept", 0f, p.restitutionPeg)
        assertF("restitutionWall 1 is kept", 1f, p.restitutionWall)
        assertF("tangentialDamping 0 is kept", 0f, p.tangentialDamping)
        assertF("movingPegTransfer 0 is kept", 0f, p.movingPegTransfer)
    }

    // -- sanitisation: §3.3 anti-tunnelling ----------------------------------------------------

    @Test
    fun aTunnellingMaxBallSpeedIsClampedUnderTheBound() {
        val p = resolve(PhysicsConfig(maxBallSpeed = 5000f)) // 20.8 u per step against a 7 u peg
        assertTrue(
            "5000 u/s must be clamped: ${p.maxStepDisplacement} u per step vs a 7 u peg",
            p.maxStepDisplacement < 7f,
        )
        assertF(
            "clamped to the margin under 7 u x 240 Hz",
            7f * 240 * PhysicsParams.ANTI_TUNNELLING_MARGIN,
            p.maxBallSpeed,
        )
        assertF("launchSpeed was already below the cap", 900f, p.launchSpeed)
    }

    /** The bound is against the smallest solid radius — whichever collider that is. */
    @Test
    fun theBoundFollowsTheSmallestSolidRadius() {
        val smallPegs = resolve(PhysicsConfig(pegRadius = 3f))
        assertTrue("3 u pegs", smallPegs.maxStepDisplacement < 3f)
        assertF(
            "§3.2's 1600 u/s is too fast for 3 u pegs",
            3f * 240 * PhysicsParams.ANTI_TUNNELLING_MARGIN,
            smallPegs.maxBallSpeed,
        )
        assertTrue(
            "the launch is capped at the new terminal speed",
            smallPegs.launchSpeed <= smallPegs.maxBallSpeed,
        )

        val smallBall = resolve(PhysicsConfig(ballRadius = 5f))
        assertTrue(
            "a 5 u ball could otherwise step across a wall line",
            smallBall.maxStepDisplacement < 5f,
        )

        val smallGems = resolve(PhysicsConfig(gemRadius = 6f))
        assertTrue("6 u gems", smallGems.maxStepDisplacement < 6f)
    }

    @Test
    fun theBoundFollowsTheStepRate() {
        val p = resolve(PhysicsConfig(physicsStepMs = 16.6667f)) // 60 Hz: 7 u x 60 = 420 u/s
        assertEquals(60, p.stepsPerSecond)
        assertTrue("at 60 Hz", p.maxStepDisplacement < 7f)
        assertF(
            "1600 u/s cannot survive 60 Hz",
            7f * 60 * PhysicsParams.ANTI_TUNNELLING_MARGIN,
            p.maxBallSpeed,
        )
        assertF("nor can a 900 u/s launch", p.maxBallSpeed, p.launchSpeed)
    }

    /**
     * The clamp is `min(configured, cap)`: a faster configured speed never resolves to a slower
     * one, and anything at or under the cap is kept exactly.
     */
    @Test
    fun theSpeedClampIsMonotonic() {
        var previous = 0f
        var configured = 100f
        while (configured < 100_000f) {
            val resolved = resolve(PhysicsConfig(maxBallSpeed = configured)).maxBallSpeed
            assertTrue("$configured resolved to $resolved, below $previous", resolved >= previous)
            assertTrue("$configured resolved above itself", resolved <= configured)
            assertTrue("$configured breaks the bound", resolved / 240f < 7f)
            previous = resolved
            configured *= 1.01f
        }
    }

    @Test
    fun aLaunchFasterThanTheTerminalSpeedIsCappedAtIt() {
        val p = resolve(PhysicsConfig(launchSpeed = 1500f, maxBallSpeed = 1200f))
        assertF("maxBallSpeed kept", 1200f, p.maxBallSpeed)
        assertF("launchSpeed capped", 1200f, p.launchSpeed)
    }

    // -- sanitisation: launcher and board ------------------------------------------------------

    @Test
    fun anAimClampAtOrPastNinetyDegreesIsCappedSoEveryLaunchGoesDown() {
        for (degrees in listOf(90f, 120f, 180f, 1e9f)) {
            val p = resolve(board = BoardConfig(launcher = LauncherDef(aimClampDegrees = degrees)))
            assertEquals(
                "aimClampDegrees $degrees",
                PhysicsParams.MAX_AIM_CLAMP_DEGREES.toDouble(),
                degrees(p.aimClampRadians),
                1e-4,
            )
            for (aimDegrees in listOf(-180f, -89f, -45f, 0f, 45f, 89f, 90f, 180f)) {
                assertTrue(
                    "clamp $degrees, aim $aimDegrees: launchVy must be downward",
                    p.launchVy(radians(aimDegrees)) > 0f,
                )
            }
        }
    }

    @Test
    fun anUnusableAimClampFallsBackToSeventyDegrees() {
        for (degrees in listOf(0f, -70f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val p = resolve(board = BoardConfig(launcher = LauncherDef(aimClampDegrees = degrees)))
            assertEquals("clamp $degrees", PhysicsParams().aimClampRadians, p.aimClampRadians, 0f)
        }
        val narrow = resolve(board = BoardConfig(launcher = LauncherDef(aimClampDegrees = 45f)))
        assertEquals("a legal clamp is kept", 45.0, degrees(narrow.aimClampRadians), 1e-5)
    }

    @Test
    fun spawnAndExitAreDerivedFromTheBoard() {
        val p = resolve(
            board = BoardConfig(
                launcherZone = YBand(40f, 160f),
                cupLane = YBand(1300f, 1400f),
                launcher = LauncherDef(pivot = BoardPoint(480f, 0f)),
            ),
            physics = PhysicsConfig(ballRadius = 20f),
        )
        assertF("spawnX is the launcher pivot's x", 480f, p.spawnX)
        assertF("spawnY is the launcher zone's midpoint", 100f, p.spawnY)
        assertF("exitY is the cup lane's bottom plus a ball radius", 1420f, p.exitY)
    }

    /** A cup lane configured above the open base must not retire balls still in the triangle. */
    @Test
    fun theExitLineNeverSitsAboveTheOpenBase() {
        val p = resolve(board = BoardConfig(cupLane = YBand(900f, 1000f)))
        assertF("floored at baseY + ballRadius", 1250f + 16f, p.exitY)
    }

    /**
     * The spawn point must clear both slanted walls by a full ball radius, or the very first step
     * would register a wall contact. Checked two ways: from the §3.1 geometry in closed form, and
     * against the walls the engine actually builds.
     */
    @Test
    fun theSpawnPointClearsBothWallsAndTheLattice() {
        val p = PhysicsParams.DEFAULT
        val board = GameConfig.DEFAULTS.board

        // §3.1: the spawn sits on the axis, 75 u below the apex; each wall leans 21.8 degrees off
        // vertical, so it is 75 sin(21.8 deg) = 27.9 u away.
        for (line in listOf(board.walls.left, board.walls.right)) {
            val distance =
                TestBoards.interiorDistance(line, p.spawnX.toDouble(), p.spawnY.toDouble())
            val closedForm = 75.0 * StrictMath.sin(Math.toRadians(21.8))
            assertEquals("§3.1 closed form", closedForm, distance, 0.05)
            assertTrue("spawn clears $line by only $distance u", distance >= p.ballRadius)
        }

        val walls = WallSegment.boardWalls(board, p.restitutionWall)
        assertEquals(2, walls.size)
        for (wall in walls) {
            assertFalse(
                "a ball at the spawn point overlaps $wall",
                wall.contact(p.spawnX, p.spawnY, p.ballRadius, Contact()),
            )
            val signed = (p.spawnX - wall.x1) * wall.nx + (p.spawnY - wall.y1) * wall.ny
            assertTrue("spawn is only $signed u inside $wall", signed >= p.ballRadius)
        }

        // Nor can it touch the first lattice row (y = 150).
        assertTrue(
            "the spawned ball reaches the first peg row",
            p.spawnY + p.ballRadius < board.latticeBand.yTop - p.pegRadius,
        )
    }

    // -- aim and launch velocity ---------------------------------------------------------------

    @Test
    fun aimZeroLaunchesExactlyStraightDownAtTheLaunchSpeed() {
        val p = PhysicsParams.DEFAULT
        assertEquals("vx", 0f, p.launchVx(0f), 0f)
        assertEquals("vy", 900f, p.launchVy(0f), 0f)
    }

    @Test
    fun aimIsMeasuredFromStraightDownPositiveTowardPlusX() {
        val p = PhysicsParams.DEFAULT
        for (degrees in listOf(30f, 70f)) {
            val a = radians(degrees)
            val vx = p.launchVx(a)
            val vy = p.launchVy(a)
            assertEquals("vx at $degrees", 900 * StrictMath.sin(a.toDouble()), vx.toDouble(), 1e-3)
            assertEquals("vy at $degrees", 900 * StrictMath.cos(a.toDouble()), vy.toDouble(), 1e-3)
            assertTrue("+$degrees heads toward +x", vx > 0f)
            assertEquals("speed at $degrees", 900.0, hypot(vx, vy), 1e-3)
        }
        assertEquals("30 degrees", 450.0, p.launchVx(radians(30f)).toDouble(), 1e-3)
        assertEquals("70 degrees", 307.818, p.launchVy(radians(70f)).toDouble(), 1e-3)
    }

    /** fdlibm's sin is odd and cos even, bit for bit: mirrored aims launch mirrored balls. */
    @Test
    fun mirroredAimsLaunchExactlyMirroredVelocities() {
        val p = PhysicsParams.DEFAULT
        var degrees = 0.25f
        while (degrees <= 70f) {
            val a = radians(degrees)
            assertEquals("vx at +-$degrees", p.launchVx(a), -p.launchVx(-a), 0f)
            assertEquals("vy at +-$degrees", p.launchVy(a), p.launchVy(-a), 0f)
            degrees += 0.25f
        }
    }

    @Test
    fun aimsBeyondTheClampLaunchExactlyAtTheClamp() {
        val p = PhysicsParams.DEFAULT
        val clamp = p.aimClampRadians
        for (degrees in listOf(70.001f, 80f, 90f, 135f, 180f, 720f)) {
            val a = radians(degrees)
            assertEquals("clampAim(+$degrees)", clamp, p.clampAim(a), 0f)
            assertEquals("clampAim(-$degrees)", -clamp, p.clampAim(-a), 0f)
            assertEquals("vx at +$degrees", p.launchVx(clamp), p.launchVx(a), 0f)
            assertEquals("vy at +$degrees", p.launchVy(clamp), p.launchVy(a), 0f)
            assertEquals("vx at -$degrees", p.launchVx(-clamp), p.launchVx(-a), 0f)
        }
        assertEquals("the largest float", clamp, p.clampAim(Float.MAX_VALUE), 0f)
        assertEquals("inside the clamp", radians(33.3f), p.clampAim(radians(33.3f)), 0f)
        assertEquals("the clamp itself is untouched", clamp, p.clampAim(clamp), 0f)
    }

    @Test
    fun aNonFiniteAimLaunchesStraightDown() {
        val p = PhysicsParams.DEFAULT
        for (aim in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals("clampAim($aim)", 0f, p.clampAim(aim), 0f)
            assertEquals("vx at $aim", 0f, p.launchVx(aim), 0f)
            assertEquals("vy at $aim", 900f, p.launchVy(aim), 0f)
        }
    }

    @Test
    fun everyLaunchHasADownwardComponent() {
        val p = PhysicsParams.DEFAULT
        var degrees = -360f
        while (degrees <= 360f) {
            assertTrue("aim $degrees", p.launchVy(radians(degrees)) > 0f)
            degrees += 0.5f
        }
        assertTrue("at the clamp", p.launchVy(p.aimClampRadians) > 0f)
        assertTrue("at minus the clamp", p.launchVy(-p.aimClampRadians) > 0f)
    }

    /** Hand-built params are not sanitised, but a launch off one still must not throw. */
    @Test
    fun clampAimNeverThrowsEvenOnANonsensicalHandBuiltClamp() {
        for (clamp in listOf(-1f, Float.NaN, Float.NEGATIVE_INFINITY)) {
            val p = PhysicsParams(aimClampRadians = clamp)
            for (aim in listOf(-1f, 0f, 0.5f, Float.NaN)) {
                p.clampAim(aim)
                p.launchVx(aim)
                p.launchVy(aim)
            }
        }
    }

    private companion object {

        const val DELTA = 1e-4

        fun assertF(message: String, expected: Float, actual: Float) =
            assertEquals(message, expected.toDouble(), actual.toDouble(), DELTA)

        fun resolve(
            physics: PhysicsConfig = PhysicsConfig(),
            board: BoardConfig = BoardConfig(),
        ): PhysicsParams =
            PhysicsParams.from(GameConfig.DEFAULTS.copy(physics = physics, board = board))

        fun stepsPerSecondFor(physicsStepMs: Float): Int =
            resolve(PhysicsConfig(physicsStepMs = physicsStepMs)).stepsPerSecond

        fun dwellStepsFor(dwellSeconds: Float): Int =
            resolve(PhysicsConfig(stuckBall = StuckBallConfig(dwellSeconds = dwellSeconds)))
                .stuckDwellSteps

        fun degrees(radians: Float): Double = Math.toDegrees(radians.toDouble())

        fun radians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

        fun hypot(x: Float, y: Float): Double = sqrt(x.toDouble() * x + y.toDouble() * y)
    }
}
