package app.krafted.candytriangle.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.krafted.candytriangle.R
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface

/**
 * Resume / Restart / Quit over a frozen board.
 *
 * In scope for D1 because a pause button with nothing behind it cannot be exercised: pausing must
 * be reachable *and* reversible for the lifecycle path (`Lifecycle.Event.ON_PAUSE`) to be
 * meaningful. **D4 restyles this**; it is deliberately plain Material here, and it is not the
 * level-complete or level-failed screen, which are D4's alone.
 *
 * While it is up, `GameCommandChannel.paused` is true: the loop has drawn one last frame and
 * parked on its monitor at zero CPU, and the `SurfaceView` keeps that last posted buffer — so this
 * dialog draws over a frozen board for free, with no snapshot and no extra rendering.
 *
 * Owner: Agent 2 (`ui/game`).
 */
@Composable
fun GamePauseDialog(
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        // A back press or an outside tap is the same intent as "Resume": a paused level must never
        // be dismissible into a state with no way back to the board.
        onDismissRequest = onResume,
        modifier = modifier,
        containerColor = NightSurface,
        titleContentColor = IcingWhite,
        textContentColor = IcingDim,
        title = {
            Text(
                text = stringResource(R.string.game_pause_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.game_pause_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.action_resume), color = CandyPink)
            }
        },
        dismissButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = androidx.compose.ui.Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onRestart) {
                    Text(stringResource(R.string.game_pause_restart), color = IcingWhite)
                }
                TextButton(onClick = onQuit) {
                    Text(stringResource(R.string.action_quit), color = IcingDim)
                }
            }
        },
    )
}

@Preview(name = "Pause dialog", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun GamePauseDialogPreview() {
    CandyTriangleTheme {
        GamePauseDialog(onResume = {}, onRestart = {}, onQuit = {})
    }
}
