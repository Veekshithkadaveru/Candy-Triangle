package app.krafted.candytriangle.level

/**
 * Shared vocabulary for every A2 data model.
 *
 * These enums are the one thing `config.json` (PRD §3.2-§7) and `levels.json` (§6.3) have in
 * common, so they live in a single file that both `ConfigDef.kt` and `LevelDef.kt` import rather
 * than each declaring their own copy.
 *
 * Every enum exposes a `fromKey` that returns `null` on an unrecognised string instead of throwing.
 * The game ships fully offline with hand-authored JSON; a typo in a level file should drop one
 * entry, not crash the app on launch.
 */

/** The four candy colours (§4.1). [configKey] is the JSON spelling, [spriteName] the drawable. */
enum class CandyColor(val configKey: String, val spriteName: String) {
    GREEN("GREEN", "can_1"),
    PURPLE("PURPLE", "can_2"),
    PINK("PINK", "can_3"),
    BLUE("BLUE", "can_4"),
    ;

    companion object {
        fun fromKey(key: String?): CandyColor? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/**
 * The seven gems (§4.2).
 *
 * [multiplier] and [introLevel] are duplicated from `config.json` deliberately: they are part of
 * the game's identity rather than a tuning knob, and having them on the enum lets level-authoring
 * and UI code reason about a gem without having loaded the config first. `ConfigLoader` still reads
 * the JSON values, and `ConfigLoaderTest` asserts the two agree.
 */
enum class GemType(
    val configKey: String,
    val spriteName: String,
    val multiplier: Int,
    val introLevel: Int,
) {
    SWEET("SWEET", "x5", 5, 3),
    BLAST("BLAST", "x10_1", 10, 6),
    LINE("LINE", "x10_cyan", 10, 11),
    SPLIT("SPLIT", "x15", 15, 15),
    EXTRA_BALL("EXTRA_BALL", "x25", 25, 21),
    MAGNET("MAGNET", "x35", 35, 25),
    SUGAR_STORM("SUGAR_STORM", "x80", 80, 31),
    ;

    companion object {
        fun fromKey(key: String?): GemType? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/** The six objective types tracked by `ObjectiveTracker` (phase B4). */
enum class ObjectiveType(val configKey: String) {
    COLLECT_CANDY("COLLECT_CANDY"),
    SCORE("SCORE"),
    COLLECT_GEM("COLLECT_GEM"),
    CLEAR_COLOR("CLEAR_COLOR"),
    CHAIN("CHAIN"),
    CUP("CUP"),
    ;

    companion object {
        fun fromKey(key: String?): ObjectiveType? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/** Peg-lattice fill pattern for a level's layout (§6.3 `layout.pattern`). */
enum class LayoutPattern(val configKey: String) {
    /** Every lattice node inside the triangle carries a peg, minus explicit holes. */
    FULL("FULL"),

    /** Only the nodes named by `layout.clusters` carry pegs. */
    CLUSTERS("CLUSTERS"),

    /** Alternating rows, for sparse late-world boards. */
    SPARSE("SPARSE"),
    ;

    companion object {
        fun fromKey(key: String?): LayoutPattern? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/** Ball skins unlocked at Candy Jar tier 1 (§7). Note the Pink jar awards [GOLD], not a pink ball. */
enum class BallSkin(val configKey: String, val spriteName: String) {
    DEFAULT("DEFAULT", "ball"),
    GREEN("GREEN", "ball_green"),
    PURPLE("PURPLE", "ball_purple"),
    BLUE("BLUE", "ball_blue"),
    GOLD("GOLD", "ball_gold"),
    ;

    companion object {
        fun fromKey(key: String?): BallSkin? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/** Candy trails unlocked at Candy Jar tier 2 (§7). */
enum class TrailType(val configKey: String) {
    NONE("NONE"),
    GREEN("GREEN"),
    PURPLE("PURPLE"),
    PINK("PINK"),
    BLUE("BLUE"),
    ;

    companion object {
        fun fromKey(key: String?): TrailType? =
            entries.firstOrNull { it.configKey.equals(key, ignoreCase = true) }
    }
}

/**
 * Level-id conventions for the single Int keyspace agreed for A2.
 *
 * §10 keys progress by `Int` level id, so the four bonus Sweet Rooms share that keyspace rather
 * than introducing a parallel set of string-keyed preferences: main levels are 1..40 and Sweet
 * Rooms are 101..104, carrying their "B1".."B4" spelling in [LevelDef.code] for display only.
 */
object LevelIds {
    const val MAIN_FIRST = 1
    const val MAIN_LAST = 40
    const val BONUS_FIRST = 101
    const val BONUS_LAST = 104

    fun isMain(id: Int): Boolean = id in MAIN_FIRST..MAIN_LAST

    fun isBonus(id: Int): Boolean = id in BONUS_FIRST..BONUS_LAST

    /** "B1".."B4" for a Sweet Room id, else null. */
    fun bonusCode(id: Int): String? = if (isBonus(id)) "B${id - BONUS_FIRST + 1}" else null

    /** Sweet Room id for a "B1".."B4" code, else null. */
    fun fromBonusCode(code: String?): Int? {
        val n = code?.trim()?.uppercase()?.removePrefix("B")?.toIntOrNull() ?: return null
        return (BONUS_FIRST + n - 1).takeIf { isBonus(it) }
    }
}
