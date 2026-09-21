package app.krafted.candytriangle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme

/**
 * Single-activity host for Candy Triangle.
 *
 * Phase A1 scope only: window setup (edge-to-edge, sticky immersive, keep-screen-on) plus a
 * placeholder surface. The navigation graph, screens and ViewModels arrive in D1-D4.
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
                CandyTrianglePlaceholder()
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

@Composable
private fun CandyTrianglePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12061F)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Candy Triangle",
            color = Color(0xFFFF4FC8),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
