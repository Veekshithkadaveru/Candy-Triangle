package app.krafted.candytriangle.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.krafted.candytriangle.R
import app.krafted.candytriangle.data.GameSettings
import app.krafted.candytriangle.ui.components.CandyBackdrop
import app.krafted.candytriangle.ui.theme.CandyCyan
import app.krafted.candytriangle.ui.theme.CandyPink
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingDim
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightSurface
import app.krafted.candytriangle.ui.theme.StatusError

private val SettingsCardShape = RoundedCornerShape(20.dp)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(context) { SettingsViewModel.factory(context) },
    )
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onVibrationChanged = settingsViewModel::setVibrateEnabled,
        onResetProgress = settingsViewModel::resetProgress,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsContent(
    settings: GameSettings,
    onBack: () -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onResetProgress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    CandyBackdrop(imageRes = R.drawable.world_4, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = IcingWhite,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_section_feedback))
            SettingsCard {
                SettingsToggleRow(
                    label = stringResource(R.string.label_vibration),
                    checked = settings.vibrateEnabled,
                    onCheckedChange = onVibrationChanged,
                )
            }

            SettingsSectionLabel(stringResource(R.string.settings_section_game))
            SettingsCard {
                SettingsActionRow(
                    label = stringResource(R.string.settings_reset_progress),
                    contentColor = StatusError,
                    onClick = { showResetConfirmation = true },
                )
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            containerColor = NightSurface,
            title = { Text(stringResource(R.string.settings_reset_title), color = IcingWhite) },
            text = { Text(stringResource(R.string.settings_reset_message), color = IcingDim) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetProgress()
                        showResetConfirmation = false
                    },
                ) { Text(stringResource(R.string.settings_reset_confirm), color = StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel), color = IcingWhite)
                }
            },
        )
    }

}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = IcingDim,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NightSurface.copy(alpha = 0.90f), SettingsCardShape)
            .border(1.dp, CandyCyan.copy(alpha = 0.55f), SettingsCardShape)
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 11.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = IcingWhite,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = IcingWhite,
                checkedTrackColor = CandyPink,
                uncheckedThumbColor = IcingDim,
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    contentColor: androidx.compose.ui.graphics.Color = IcingWhite,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        Text(text = "›", style = MaterialTheme.typography.titleLarge, color = contentColor)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun SettingsScreenPreview() {
    CandyTriangleTheme {
        SettingsContent(GameSettings(), {}, {}, {})
    }
}
