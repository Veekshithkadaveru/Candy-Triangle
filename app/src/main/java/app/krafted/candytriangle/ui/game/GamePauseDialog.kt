package app.krafted.candytriangle.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.krafted.candytriangle.R
import app.krafted.candytriangle.ui.components.CandyPrimaryButton
import app.krafted.candytriangle.ui.components.CandySecondaryButton
import app.krafted.candytriangle.ui.components.candyDialogEntrance
import app.krafted.candytriangle.ui.theme.CandyCyan
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface

/**
 * Resume / Restart / Quit over a frozen board.
 *
 * Pausing must be reachable and reversible for the lifecycle path (`Lifecycle.Event.ON_PAUSE`)
 * to be meaningful. The custom neon card deliberately matches the rest of the app instead of
 * falling back to platform alert-dialog chrome.
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
    Dialog(
        // A back press or an outside tap is the same intent as "Resume": a paused level must never
        // be dismissible into a state with no way back to the board.
        onDismissRequest = onResume,
    ) {
        Column(
            modifier = modifier
                .candyDialogEntrance()
                .fillMaxWidth()
                .background(NightSurface, RoundedCornerShape(28.dp))
                .border(2.dp, CandyPink.copy(alpha = 0.9f), RoundedCornerShape(28.dp))
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.game_pause_title),
                style = MaterialTheme.typography.headlineMedium,
                color = IcingWhite,
            )
            Text(
                text = stringResource(R.string.game_pause_message),
                style = MaterialTheme.typography.bodyMedium,
                color = IcingDim,
                textAlign = TextAlign.Center,
            )
            CandyPrimaryButton(text = stringResource(R.string.action_resume), onClick = onResume)
            CandySecondaryButton(
                text = stringResource(R.string.game_pause_restart),
                onClick = onRestart,
                accent = CandyCyan,
            )
            CandySecondaryButton(
                text = stringResource(R.string.action_quit),
                onClick = onQuit,
                accent = IcingDim,
            )
        }
    }
}

@Preview(name = "Pause dialog", showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun GamePauseDialogPreview() {
    CandyTriangleTheme {
        GamePauseDialog(onResume = {}, onRestart = {}, onQuit = {})
    }
}
