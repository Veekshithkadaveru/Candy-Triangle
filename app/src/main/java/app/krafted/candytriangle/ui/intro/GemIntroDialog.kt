package app.krafted.candytriangle.ui.intro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.GemType
import app.krafted.candytriangle.ui.components.CandyPrimaryButton
import app.krafted.candytriangle.ui.components.candyDialogEntrance
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface

private val GemIntroShape = RoundedCornerShape(28.dp)

/** One-time mechanic explanation shown on the gem's configured introduction level. */
@Composable
fun GemIntroDialog(
    gem: GemType,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onContinue) {
        Column(
            modifier = modifier
                .candyDialogEntrance()
                .fillMaxWidth()
                .background(NightSurface, GemIntroShape)
                .border(2.dp, CandyGold.copy(alpha = 0.85f), GemIntroShape)
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.gem_intro_new).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = CandyGold,
            )
            Spacer(Modifier.height(12.dp))
            Image(
                painter = painterResource(gem.drawableRes()),
                contentDescription = stringResource(gem.nameRes()),
                modifier = Modifier
                    .size(116.dp)
                    .background(CandyGold.copy(alpha = 0.10f), CircleShape)
                    .padding(8.dp),
            )
            Text(
                text = stringResource(gem.nameRes()),
                style = MaterialTheme.typography.headlineMedium,
                color = IcingWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.gem_intro_multiplier, gem.multiplier),
                style = MaterialTheme.typography.titleLarge,
                color = CandyGold,
            )
            Text(
                text = stringResource(gem.descriptionRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = IcingDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(22.dp))
            CandyPrimaryButton(text = stringResource(R.string.gem_intro_action), onClick = onContinue)
        }
    }
}

private fun GemType.drawableRes(): Int = when (this) {
    GemType.SWEET -> R.drawable.x5
    GemType.BLAST -> R.drawable.x10_1
    GemType.LINE -> R.drawable.x10_cyan
    GemType.SPLIT -> R.drawable.x15
    GemType.EXTRA_BALL -> R.drawable.x25
    GemType.MAGNET -> R.drawable.x35
    GemType.SUGAR_STORM -> R.drawable.x80
}

private fun GemType.nameRes(): Int = when (this) {
    GemType.SWEET -> R.string.gem_name_sweet
    GemType.BLAST -> R.string.gem_name_blast
    GemType.LINE -> R.string.gem_name_line
    GemType.SPLIT -> R.string.gem_name_split
    GemType.EXTRA_BALL -> R.string.gem_name_extra_ball
    GemType.MAGNET -> R.string.gem_name_magnet
    GemType.SUGAR_STORM -> R.string.gem_name_sugar_storm
}

private fun GemType.descriptionRes(): Int = when (this) {
    GemType.SWEET -> R.string.gem_intro_sweet
    GemType.BLAST -> R.string.gem_intro_blast
    GemType.LINE -> R.string.gem_intro_line
    GemType.SPLIT -> R.string.gem_intro_split
    GemType.EXTRA_BALL -> R.string.gem_intro_extra_ball
    GemType.MAGNET -> R.string.gem_intro_magnet
    GemType.SUGAR_STORM -> R.string.gem_intro_sugar_storm
}

@Preview(showBackground = true)
@Composable
private fun GemIntroDialogPreview() {
    CandyTriangleTheme { GemIntroDialog(GemType.BLAST, {}) }
}
