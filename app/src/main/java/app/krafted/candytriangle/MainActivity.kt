package app.krafted.candytriangle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.krafted.candytriangle.ui.nav.CandyNavHost
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme

/**
 * Single-activity host for Candy Triangle.
 *
 * Owns exactly two things: the window setup from phase A1 (edge-to-edge, sticky immersive,
 * keep-screen-on) and the one `CandyNavHost` that carries every screen. Screens, ViewModels and
 * routes all live under `ui/`; nothing but the navigation graph is referenced from here, so D3 and
 * D4 add destinations without touching this file.
 *
 * The activity is portrait-locked and swallows `configChanges` (see `AndroidManifest.xml`), so a
 * rotation can neither recreate it nor destroy a level in progress.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A drop lasts ~8s with no touch input while the ball is in flight; the screen must
        // not dim or lock mid-drop.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyImmersiveMode()

        setContent {
            CandyTriangleTheme {
                CandyNavHost()
            }
        }
    }

    /** System bars can reappear after a transient swipe or a config change; re-hide them. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Sticky immersive: a swipe reveals the bars transiently, then they auto-hide,
            // so an edge swipe never permanently resizes the board mid-level.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
