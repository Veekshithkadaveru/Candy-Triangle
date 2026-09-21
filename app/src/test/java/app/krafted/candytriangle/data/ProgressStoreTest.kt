package app.krafted.candytriangle.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.level.TrailType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rules from §10 (key schema), §5.3 (crowns), §6.2 (world gates) and §7 (Candy Jar) that the rest
 * of the game silently depends on.
 *
 * Most cases run against [FakePreferencesDataStore] for speed; [survivesStoreRebuildOnRealFile]
 * runs against a real temp-file `PreferenceDataStore` so the JVM suite also proves the on-device
 * serialisation path, which §11 otherwise never exercises (no emulator, no Robolectric).
 */
class ProgressStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val preferences = FakePreferencesDataStore()
    private val store = ProgressStore(preferences)

    // ---------------------------------------------------------------- level results

    /**
     * The one rule a replay must never break: levels are replayable and a replay is usually a
     * worse run, so a bad second attempt cannot cost the player crowns (which gate worlds, §6.2)
     * or a high score.
     */
    @Test
    fun recordLevelResultMaxMergesCrownsAndScore() = runTest {
        store.recordLevelResult(levelId = 5, crowns = 3, score = 5_000)
        store.recordLevelResult(levelId = 5, crowns = 1, score = 100)

        assertEquals(3, store.crownsFor(5).first())
        assertEquals(5_000, store.bestScoreFor(5).first())
    }

    @Test
    fun recordLevelResultKeepsAnImprovement() = runTest {
        store.recordLevelResult(levelId = 5, crowns = 1, score = 100)
        store.recordLevelResult(levelId = 5, crowns = 3, score = 5_000)

        assertEquals(3, store.crownsFor(5).first())
        assertEquals(5_000, store.bestScoreFor(5).first())
    }

    /** Crowns and score merge independently: a low-crown run can still set a high score. */
    @Test
    fun recordLevelResultMergesTheTwoHalvesIndependently() = runTest {
        store.recordLevelResult(levelId = 5, crowns = 3, score = 1_000)
        store.recordLevelResult(levelId = 5, crowns = 1, score = 9_000)

        assertEquals(3, store.crownsFor(5).first())
        assertEquals(9_000, store.bestScoreFor(5).first())
    }

    /** A caller bug upstream must not be able to poison the save with impossible values. */
    @Test
    fun recordLevelResultClampsCrownsAndScore() = runTest {
        store.recordLevelResult(levelId = 5, crowns = 9, score = 10)
        store.recordLevelResult(levelId = 6, crowns = -4, score = -10)

        assertEquals(ProgressStore.MAX_CROWNS_PER_LEVEL, store.crownsFor(5).first())
        assertEquals(0, store.crownsFor(6).first())
        assertEquals(0, store.bestScoreFor(6).first())
    }

    @Test
    fun unplayedLevelsReadAsZero() = runTest {
        assertEquals(0, store.crownsFor(17).first())
        assertEquals(0, store.bestScoreFor(17).first())
        assertFalse(store.progress.first().isCleared(17))
    }

    /** Pins §10's key spelling: other tooling and any future migration keys off these strings. */
    @Test
    fun writesTheSection10KeyNames() = runTest {
        store.recordLevelResult(levelId = 7, crowns = 2, score = 900)
        store.bankCandies(mapOf(CandyColor.PINK to 5))

        val raw = preferences.data.first()
        assertEquals(2, raw[intPreferencesKey("lvl_7_crowns")])
        assertEquals(900, raw[intPreferencesKey("lvl_7_best")])
        assertEquals(5, raw[intPreferencesKey("jar_pink")])
    }

    // ---------------------------------------------------------------- world gates

    /**
     * §6.2's gates compare against main levels only. "Max Available 30 / 60 / 90" is exactly
     * 10 / 20 / 30 main levels times 3 crowns, so a Sweet Room's crowns must not count — if they
     * did, a player could unlock World 2 without clearing the World 1 levels the gate is about.
     */
    @Test
    fun totalCrownsExcludesSweetRooms() = runTest {
        store.recordLevelResult(levelId = 1, crowns = 3, score = 100)
        store.recordLevelResult(levelId = 40, crowns = 2, score = 100)
        store.recordLevelResult(levelId = 101, crowns = 3, score = 9_999) // Sweet Room B1

        assertEquals(5, store.totalCrowns.first())
        assertEquals(5, store.progress.first().totalCrowns)
        // The Sweet Room result itself is still stored and readable, just not counted.
        assertEquals(3, store.crownsFor(101).first())
    }

    @Test
    fun highestClearedMainLevelIgnoresSweetRoomsAndUnclearedLevels() = runTest {
        store.recordLevelResult(levelId = 10, crowns = 1, score = 100)
        store.recordLevelResult(levelId = 11, crowns = 0, score = 40) // failed, not cleared
        store.recordLevelResult(levelId = 104, crowns = 3, score = 100) // Sweet Room B4

        val progress = store.progress.first()
        assertEquals(10, progress.highestClearedMainLevel)
        assertTrue(progress.isCleared(10))
        assertFalse(progress.isCleared(11))
    }

    // ---------------------------------------------------------------- candy jars

    /** Jars are lifetime totals (§7) — every level's haul adds to what is already banked. */
    @Test
    fun bankCandiesAccumulatesAcrossCalls() = runTest {
        store.bankCandies(mapOf(CandyColor.GREEN to 12, CandyColor.PINK to 3))
        store.bankCandies(mapOf(CandyColor.GREEN to 8))

        val jars = store.jarCounts.first()
        assertEquals(20, jars[CandyColor.GREEN])
        assertEquals(3, jars[CandyColor.PINK])
        assertEquals(0, jars[CandyColor.PURPLE])
        assertEquals(0, jars[CandyColor.BLUE])
    }

    /**
     * §7 banks candies from failed levels too (`config.json` `jar.bankCandiesOnFailedLevels`), so
     * a losing run is an ordinary bank call with no crowns recorded.
     */
    @Test
    fun bankCandiesWorksWithoutALevelResult() = runTest {
        store.bankCandies(mapOf(CandyColor.BLUE to 7))

        assertEquals(7, store.jarCounts.first()[CandyColor.BLUE])
        assertEquals(0, store.totalCrowns.first())
    }

    /** A jar is a lifetime total; nothing may drain it. */
    @Test
    fun bankCandiesIgnoresNonPositiveDeltas() = runTest {
        store.bankCandies(mapOf(CandyColor.GREEN to 10))
        store.bankCandies(mapOf(CandyColor.GREEN to -5, CandyColor.BLUE to 0))

        val jars = store.jarCounts.first()
        assertEquals(10, jars[CandyColor.GREEN])
        assertEquals(0, jars[CandyColor.BLUE])
    }

    // ---------------------------------------------------------------- §7 reward tiers

    /** Tier 1 at 40 candies: 39 is still locked, 40 unlocks — and Pink awards GOLD, not pink. */
    @Test
    fun ballSkinUnlocksAtTheTier1Boundary() = runTest {
        store.bankCandies(mapOf(CandyColor.GREEN to 39, CandyColor.PINK to 39))
        assertEquals(setOf(BallSkin.DEFAULT), store.progress.first().unlockedBallSkins())

        store.bankCandies(mapOf(CandyColor.GREEN to 1, CandyColor.PINK to 1))
        assertEquals(
            setOf(BallSkin.DEFAULT, BallSkin.GREEN, BallSkin.GOLD),
            store.progress.first().unlockedBallSkins(),
        )
    }

    /** Tier 2 at 120 candies. The trail colour does match its jar, unlike the tier 1 skin. */
    @Test
    fun trailUnlocksAtTheTier2Boundary() = runTest {
        store.bankCandies(mapOf(CandyColor.PURPLE to 119))
        assertEquals(setOf(TrailType.NONE), store.progress.first().unlockedTrails())

        store.bankCandies(mapOf(CandyColor.PURPLE to 1))
        assertEquals(
            setOf(TrailType.NONE, TrailType.PURPLE),
            store.progress.first().unlockedTrails(),
        )
    }

    /** Tier 3 at 200 candies opens Sweet Room B1..B4 (level ids 101..104). */
    @Test
    fun sweetRoomUnlocksAtTheTier3Boundary() = runTest {
        store.bankCandies(mapOf(CandyColor.BLUE to 199))
        assertEquals(emptySet<Int>(), store.progress.first().unlockedSweetRooms())

        store.bankCandies(mapOf(CandyColor.BLUE to 1))
        assertEquals(setOf(104), store.progress.first().unlockedSweetRooms())
    }

    /** Tiers are cumulative: a full jar has handed over all three rewards, not just the last. */
    @Test
    fun aFullJarGrantsEveryTier() = runTest {
        store.bankCandies(mapOf(CandyColor.PINK to 200))

        val progress = store.progress.first()
        assertTrue(progress.unlockedBallSkins().contains(BallSkin.GOLD))
        assertTrue(progress.unlockedTrails().contains(TrailType.PINK))
        assertEquals(setOf(103), progress.unlockedSweetRooms())
        assertEquals(3, JarUnlocks.tierFor(progress.jarCount(CandyColor.PINK)))
    }

    // ---------------------------------------------------------------- cosmetics & intros

    @Test
    fun equippedCosmeticsRoundTrip() = runTest {
        assertEquals(BallSkin.DEFAULT, store.equippedBallSkin.first())
        assertEquals(TrailType.NONE, store.equippedTrail.first())

        store.equipBallSkin(BallSkin.GOLD)
        store.equipTrail(TrailType.BLUE)

        assertEquals(BallSkin.GOLD, store.equippedBallSkin.first())
        assertEquals(TrailType.BLUE, store.equippedTrail.first())
        assertEquals(BallSkin.GOLD, store.progress.first().equippedBallSkin)
        assertEquals(TrailType.BLUE, store.progress.first().equippedTrail)
    }

    @Test
    fun seenGemIntrosGrowAndAreIdempotent() = runTest {
        assertEquals(emptySet<GemType>(), store.seenGemIntros.first())

        store.markGemIntroSeen(GemType.SWEET)
        store.markGemIntroSeen(GemType.BLAST)
        store.markGemIntroSeen(GemType.SWEET)

        assertEquals(setOf(GemType.SWEET, GemType.BLAST), store.seenGemIntros.first())
    }

    /**
     * A renamed enum constant, a downgrade or a hand-edited save must degrade to the default
     * cosmetic. A ball skin is never worth a crash loop on launch.
     */
    @Test
    fun corruptStoredEnumsFallBackToDefaults() = runTest {
        val corrupt = FakePreferencesDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("ball_skin") to "RAINBOW",
                stringPreferencesKey("trail") to "",
                stringSetPreferencesKey("seen_intros") to setOf("SWEET", "NOT_A_GEM"),
            ),
        )
        val corruptStore = ProgressStore(corrupt)

        assertEquals(BallSkin.DEFAULT, corruptStore.equippedBallSkin.first())
        assertEquals(TrailType.NONE, corruptStore.equippedTrail.first())
        // The one readable gem survives; the junk entry is dropped rather than throwing.
        assertEquals(setOf(GemType.SWEET), corruptStore.seenGemIntros.first())
    }

    // ---------------------------------------------------------------- progress reset

    /**
     * D4's Progress Reset. Everything this store owns goes; the §10 settings flags deliberately
     * survive, since a player replaying from level 1 did not ask for their music to come back on.
     */
    @Test
    fun resetProgressClearsEverythingItOwnsAndKeepsSettings() = runTest {
        val settings = SettingsStore(preferences)
        settings.setMusicEnabled(false)
        settings.setSoundEnabled(false)
        settings.setVibrateEnabled(false)

        store.recordLevelResult(levelId = 1, crowns = 3, score = 5_000)
        store.recordLevelResult(levelId = 101, crowns = 2, score = 8_000)
        store.bankCandies(mapOf(CandyColor.GREEN to 200, CandyColor.PINK to 44))
        store.equipBallSkin(BallSkin.GREEN)
        store.equipTrail(TrailType.GREEN)
        store.markGemIntroSeen(GemType.MAGNET)

        store.resetProgress()

        assertEquals(PlayerProgress(), store.progress.first())
        assertEquals(0, store.crownsFor(1).first())
        assertEquals(0, store.bestScoreFor(1).first())
        assertEquals(0, store.crownsFor(101).first())
        assertEquals(0, store.totalCrowns.first())
        assertEquals(setOf(BallSkin.DEFAULT), store.progress.first().unlockedBallSkins())

        assertFalse(settings.musicEnabled.first())
        assertFalse(settings.soundEnabled.first())
        assertFalse(settings.vibrateEnabled.first())
    }

    // ---------------------------------------------------------------- real file round-trip

    /**
     * The on-device path: a real `PreferenceDataStore` over a temp file, written by one store
     * instance and read back by a second one built over the same file after the first has been
     * torn down. This is the closest a JVM-only suite gets to "the save survives an app restart",
     * and it is the reason [ProgressStore] takes its `DataStore` by constructor instead of using
     * the `Context` delegate.
     */
    @Test
    fun survivesStoreRebuildOnRealFile() = runTest {
        val file = File(tempFolder.newFolder(), "progress.preferences_pb")

        val writerScope = CoroutineScope(Dispatchers.IO + Job())
        val writer = ProgressStore(
            PreferenceDataStoreFactory.create(scope = writerScope, produceFile = { file }),
        )
        writer.recordLevelResult(levelId = 12, crowns = 3, score = 7_400)
        writer.recordLevelResult(levelId = 12, crowns = 1, score = 200) // must not regress
        writer.bankCandies(mapOf(CandyColor.PINK to 41))
        writer.equipBallSkin(BallSkin.GOLD)
        writer.markGemIntroSeen(GemType.BLAST)

        // DataStore refuses two live instances over one file, so drain the first completely.
        writerScope.cancel()
        writerScope.coroutineContext.job.join()
        assertTrue("DataStore should have written the file", file.exists())

        val readerScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val reader = ProgressStore(
                PreferenceDataStoreFactory.create(scope = readerScope, produceFile = { file }),
            )
            val reloaded = reader.progress.first()

            assertEquals(3, reloaded.crownsFor(12))
            assertEquals(7_400, reloaded.bestScoreFor(12))
            assertEquals(3, reloaded.totalCrowns)
            assertEquals(41, reloaded.jarCount(CandyColor.PINK))
            assertEquals(BallSkin.GOLD, reloaded.equippedBallSkin)
            assertEquals(setOf(GemType.BLAST), reloaded.seenGemIntros)
            assertTrue(reloaded.unlockedBallSkins().contains(BallSkin.GOLD))
        } finally {
            readerScope.cancel()
        }
    }
}
