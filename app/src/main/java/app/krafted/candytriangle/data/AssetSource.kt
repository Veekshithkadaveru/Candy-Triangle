package app.krafted.candytriangle.data

import android.content.res.AssetManager
import java.io.IOException

/**
 * Reads a text asset by path.
 *
 * `ConfigLoader` and `LevelRepository` both need JSON under `assets/`, but neither takes an
 * [AssetManager] directly: the §11 verification suite is JVM-only (no Robolectric, no emulator),
 * so the asset read has to be substitutable. Tests pass a lambda returning a fixture string.
 */
fun interface AssetSource {

    /**
     * UTF-8 text of [path] relative to `assets/`, or `null` when the asset does not exist.
     *
     * Absence is a normal, non-exceptional outcome: `levels.json` is authored in phase C1 and is
     * legitimately missing before then, and callers degrade rather than crash.
     */
    fun readText(path: String): String?
}

/** [AssetSource] backed by the APK's `assets/` directory. */
class AndroidAssetSource(private val assets: AssetManager) : AssetSource {

    override fun readText(path: String): String? =
        try {
            assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            // AssetManager.open throws FileNotFoundException (an IOException) for a missing
            // asset; that is the "absent" contract above, not an error worth propagating.
            null
        }
}
