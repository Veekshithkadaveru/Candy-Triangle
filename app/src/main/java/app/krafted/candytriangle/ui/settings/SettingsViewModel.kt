package app.krafted.candytriangle.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.krafted.candytriangle.appContainer
import app.krafted.candytriangle.data.GameSettings
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.data.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Destination-scoped owner for the persisted settings and the destructive reset command. */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val progressStore: ProgressStore,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val settings: StateFlow<GameSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameSettings(),
    )

    fun setVibrateEnabled(enabled: Boolean) = update { settingsStore.setVibrateEnabled(enabled) }

    fun resetProgress() = update { progressStore.resetProgress() }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch(workDispatcher) { block() }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val application = context.applicationContext
            return viewModelFactory {
                initializer {
                    val container = application.appContainer
                    SettingsViewModel(container.settingsStore, container.progressStore)
                }
            }
        }
    }
}
