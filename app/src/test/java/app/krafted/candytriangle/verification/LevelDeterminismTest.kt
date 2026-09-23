package app.krafted.candytriangle.verification

import app.krafted.candytriangle.board.LevelBoard
import app.krafted.candytriangle.level.GameEvent
import app.krafted.candytriangle.level.LevelDef
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * §11 #1, determinism on the real levels: the same aim on a fresh `LevelBoard` of the same level
 * produces the same trajectory — every ball, split clones included, after every step — and the same
 * `GameEvent` sequence, 100 times out of 100.
 *
 * Bit-identical is what the engine promises (B1 notes 12, 17), so each run is fingerprinted by an
 * FNV-1a hash over the raw float bits of every ball's (x, y, vx, vy) after every step; the spec's
 * 1e-5 tolerance is checked separately on coordinates sampled every [SAMPLE_EVERY] steps, so a
 * mismatch report says how far apart the runs drifted.
 *
 * Levels run on a thread pool (one fresh board per run, nothing shared), which also re-proves B1's
 * "worlds on separate threads agree" on real layouts.
 */
class LevelDeterminismTest {

    @Test
    fun hundredReplaysOfEachAimOnEveryLevelAreIdentical() {
        val started = System.nanoTime()
        val results = parallelPerLevel { level ->
            val problems = ArrayList<String>()
            var drops = 0
            var steps = 0L
            for (aimDeg in AIMS_DEGREES) {
                val reference = runDrop(level, degrees(aimDeg))
                drops++
                steps += reference.steps
                if (reference.capped) problems += "aim $aimDeg°: drop hit the ${LevelBoard.DEFAULT_MAX_DROP_STEPS}-step cap"
                if (reference.dropCompleted != 1) {
                    problems += "aim $aimDeg°: ${reference.dropCompleted} DropCompleted events for one drop " +
                        "(event capture or session bookkeeping is off)"
                }
                for (run in 1 until REPLAYS) {
                    val trace = runDrop(level, degrees(aimDeg))
                    drops++
                    steps += trace.steps
                    val diff = reference.diff(trace)
                    if (diff != null) {
                        problems += "aim $aimDeg°, replay $run: $diff"
                        break
                    }
                }
            }
            LevelResult(problems, drops, steps)
        }
        val failures = LevelFailures("§11.1 determinism (single drop × $REPLAYS)")
        var drops = 0
        var steps = 0L
        for ((level, r) in results) {
            drops += r.drops
            steps += r.steps
            r.problems.forEach { failures.add(level, it) }
        }
        println(
            "LevelDeterminismTest: ${results.size} levels × ${AIMS_DEGREES.size} aims × $REPLAYS replays = " +
                "$drops drops, $steps steps, ${(System.nanoTime() - started) / 1_000_000} ms",
        )
        assertTrue("no levels ran", results.isNotEmpty())
        failures.assertNone()
    }

    @Test
    fun aFixedFiveShotSequenceReplaysToTheSameSessionState() {
        val results = parallelPerLevel { level ->
            val reference = runSequence(level)
            val problems = ArrayList<String>()
            if (reference.isEmpty()) problems += "the first launch was refused"
            for (run in 1 until SEQUENCE_REPLAYS) {
                val trace = runSequence(level)
                if (trace != reference) {
                    val at = reference.indices.firstOrNull { it >= trace.size || reference[it] != trace[it] }
                        ?: reference.size
                    problems += "replay $run diverges at drop ${at + 1}: " +
                        "expected ${reference.getOrNull(at)}, got ${trace.getOrNull(at)}"
                    break
                }
            }
            LevelResult(problems, SEQUENCE_REPLAYS * reference.size, 0)
        }
        val failures = LevelFailures("§11.1 determinism (5-shot sequence × $SEQUENCE_REPLAYS)")
        for ((level, r) in results) r.problems.forEach { failures.add(level, it) }
        failures.assertNone()
    }

    // -- one drop --------------------------------------------------------------------------------

    private class Trace(
        val steps: Int,
        val capped: Boolean,
        val hash: Long,
        val samples: DoubleArray,
        val events: List<GameEvent>,
        val dropCompleted: Int,
    ) {
        /** Null when identical; otherwise a description of the first difference. */
        fun diff(other: Trace): String? {
            if (events != other.events) {
                val i = events.indices.firstOrNull { it >= other.events.size || events[it] != other.events[it] }
                    ?: events.size
                return "event #$i differs: ${events.getOrNull(i)} vs ${other.events.getOrNull(i)} " +
                    "(${events.size} vs ${other.events.size} events)"
            }
            if (steps != other.steps) return "drop took ${other.steps} steps, reference ${steps}"
            if (samples.size != other.samples.size) {
                return "sampled ${other.samples.size} coordinates, reference ${samples.size}"
            }
            var worst = 0.0
            for (i in samples.indices) worst = max(worst, abs(samples[i] - other.samples[i]))
            if (worst > TOLERANCE) return "sampled coordinates drift by $worst (> $TOLERANCE)"
            if (hash != other.hash) {
                return "trajectory bit-hash differs (sampled drift $worst, within tolerance) — not bit-identical"
            }
            return null
        }
    }

    private fun runDrop(level: LevelDef, aim: Float): Trace {
        val board = RealLevels.board(level)
        val recorder = EventRecorder.start(board.session)
        val samples = ArrayList<Double>()
        var hash = FNV_OFFSET
        var steps = 0
        try {
            checkNotNull(board.launch(aim)) { "launch refused on a fresh board" }
            val cap = LevelBoard.DEFAULT_MAX_DROP_STEPS
            while (board.isDropActive && steps < cap) {
                board.world.step()
                steps++
                for (ball in board.world.balls) {
                    hash = mix(hash, ball.id)
                    hash = mix(hash, ball.x.toRawBits())
                    hash = mix(hash, ball.y.toRawBits())
                    hash = mix(hash, ball.vx.toRawBits())
                    hash = mix(hash, ball.vy.toRawBits())
                    if (steps % SAMPLE_EVERY == 0) {
                        samples += ball.id.toDouble()
                        samples += ball.x.toDouble()
                        samples += ball.y.toDouble()
                    }
                }
            }
        } finally {
            recorder.stop()
        }
        val events = recorder.events.toList()
        return Trace(
            steps = steps,
            capped = board.isDropActive,
            hash = hash,
            samples = samples.toDoubleArray(),
            events = events,
            dropCompleted = events.count { it is GameEvent.DropCompleted },
        )
    }

    // -- a five-shot sequence --------------------------------------------------------------------

    /** Session state after each drop of [SEQUENCE_DEGREES]; stops early once a launch is refused. */
    private fun runSequence(level: LevelDef): List<String> {
        val board = RealLevels.board(level)
        val recorder = EventRecorder.start(board.session)
        val out = ArrayList<String>()
        try {
            for (aimDeg in SEQUENCE_DEGREES) {
                board.launch(degrees(aimDeg)) ?: break
                board.stepUntilDropEnds()
                val s = board.session
                val progress = level.objectives.map { s.objectiveTracker.getProgress(it) }
                val candyBits = board.candies.joinToString("") { if (it.active) "1" else "0" }
                val gemBits = board.layout.gems.joinToString("") { if (it.active) "1" else "0" }
                out += "score=${s.totalScore} balls=${s.remainingBalls} " +
                    "collected=${s.collectedByColor.toSortedMap()} progress=$progress " +
                    "complete=${s.isLevelComplete} failed=${s.isLevelFailed} " +
                    "candies=$candyBits gems=$gemBits events=${recorder.events.size} " +
                    "eventsHash=${recorder.events.hashCode()} step=${board.world.stepCount}"
            }
        } finally {
            recorder.stop()
        }
        return out
    }

    // -- plumbing --------------------------------------------------------------------------------

    private class LevelResult(val problems: List<String>, val drops: Int, val steps: Long)

    private fun parallelPerLevel(work: (LevelDef) -> LevelResult): List<Pair<LevelDef, LevelResult>> {
        val levels = RealLevels.levels
        RealLevels.config // load once, before the workers race for the lazy
        val pool = Executors.newFixedThreadPool(max(1, Runtime.getRuntime().availableProcessors()))
        try {
            val futures = levels.map { level -> pool.submit(Callable { work(level) }) }
            return levels.zip(futures.map { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mix(h: Long, v: Int): Long {
        var x = h
        for (shift in 0 until 32 step 8) {
            x = x xor ((v ushr shift) and 0xFF).toLong()
            x *= FNV_PRIME
        }
        return x
    }

    private companion object {
        /** −40°, straight down, +27°: left, centre (row 0's axis peg, B1 note 16) and right. */
        val AIMS_DEGREES = listOf(-40.0, 0.0, 27.0)
        const val REPLAYS = 100

        val SEQUENCE_DEGREES = listOf(-40.0, 0.0, 27.0, -63.0, 11.5)
        const val SEQUENCE_REPLAYS = 20

        const val SAMPLE_EVERY = 16
        const val TOLERANCE = 1e-5

        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}
