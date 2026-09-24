package app.krafted.candytriangle.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.krafted.candytriangle.appContainer
import app.krafted.candytriangle.data.ConfigLoader
import app.krafted.candytriangle.data.PlayerProgress
import app.krafted.candytriangle.data.ProgressStore
import app.krafted.candytriangle.level.GameConfig
import app.krafted.candytriangle.level.LevelDef
import app.krafted.candytriangle.level.LevelRepository
import app.krafted.candytriangle.ui.intro.LevelIntroMapper
import app.krafted.candytriangle.ui.intro.LevelIntroUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for `LevelMapScreen` (D3): the map itself, plus the `LevelIntroDialog` it opens.
 *
 * Collaborators by constructor, never from a `Context` — the same JVM-testability rule as
 * `JarViewModel` and `GameViewModel`; [factory] is the only `Context` touch.
 *
 * FROZEN PUBLIC API (D3 lead's skeleton): [uiState], [intro], [openLevel], [dismissIntro],
 * [INITIAL] and [factory]. `LevelMapScreen` is written against exactly these.
 *
 * ## Opening a level
 *
 * A tap is re-checked against the live store and the parsed config ([MapStateMapper.statusOf]),
 * never against the [uiState] snapshot that was on screen: `uiState` is `WhileSubscribed`, so
 * with no subscriber it can be arbitrarily stale, and a lock decision read off a stale frame could
 * open a level the player has not earned. Taps race — the catalogue read can suspend — so each new
 * tap cancels the one still in flight: **the last tap wins**, even when it lands on a locked node
 * and opens nothing.
 *
 * Owner: D3 Agent A (map logic).
 */
class LevelMapViewModel(
    private val progressStore: ProgressStore,
    private val configLoader: ConfigLoader,
    private val levelRepository: LevelRepository,
    /**
     * Builds the intro for a level the gate has let through.
     *
     * A seam, the same precedent as `GameViewModel`'s `launchRefusalsOf`: `LevelIntroMapper` is
     * another D3 agent's file and lands on its own schedule, so the JVM tests substitute a fake
     * here rather than depend on it. [factory] and every production path use the real mapper.
     */
    private val introMapper: (LevelDef, PlayerProgress, GameConfig?) -> LevelIntroUiState =
        LevelIntroMapper::map,
) : ViewModel() {

    /**
     * `null` first, then the parsed config — exactly `JarViewModel`'s handover. The leading `null`
     * maps against `GameConfig.DEFAULTS`, which is what `config.json` ships anyway, so the map is
     * right on its first frame and quietly re-maps if the asset retuned a gate.
     * `ConfigLoader.config()` caches and never throws.
     */
    private val configFlow: Flow<GameConfig?> = flow {
        emit(null)
        emit(configLoader.config())
    }

    /** The map. Starts at [INITIAL] (`loaded = false`) until the first DataStore read lands. */
    val uiState: StateFlow<LevelMapUiState> =
        combine(progressStore.progress, configFlow) { progress, config ->
            MapStateMapper.map(progress, config)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), INITIAL)

    private val _intro = MutableStateFlow<LevelIntroUiState?>(null)

    /** The intro dialog to show, or `null` for none. */
    val intro: StateFlow<LevelIntroUiState?> = _intro.asStateFlow()

    /** The open still in flight, if any. Main thread only. */
    private var opening: Job? = null

    /**
     * A node was tapped. Opens [levelId]'s intro if that node is playable **now** — re-derived from
     * the store and the config, never from a UI snapshot — else does nothing at all. A level the
     * catalogue does not know is also a no-op.
     */
    fun openLevel(levelId: Int) {
        opening?.cancel()
        opening = viewModelScope.launch {
            val config = configLoader.config()
            val progress = progressStore.progress.first()
            val status = MapStateMapper.statusOf(levelId, progress, config)
            if (status == NodeStatus.LOCKED) return@launch
            val level = levelRepository.level(levelId) ?: return@launch
            _intro.value = introMapper(level, progress, config)
        }
    }

    /**
     * Closes the intro. Also called just before navigating into the level.
     *
     * Cancels an open still in flight as well, so a slow catalogue read cannot pop a dialog back up
     * after the player has already dismissed it.
     */
    fun dismissIntro() {
        opening?.cancel()
        opening = null
        _intro.value = null
    }

    companion object {

        /** Long enough to survive a rotation or a brief backgrounding without re-reading DataStore. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** A fresh install against the default config, `loaded = false`. Lazy so that merely
         *  loading this class never runs the mapper. */
        val INITIAL: LevelMapUiState by lazy {
            MapStateMapper.map(PlayerProgress(), null).copy(loaded = false)
        }

        /** Pulls the three collaborators out of `Context.appContainer`. Never call from a `@Preview`. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val application = context.applicationContext
            return viewModelFactory {
                initializer {
                    val container = application.appContainer
                    LevelMapViewModel(
                        progressStore = container.progressStore,
                        configLoader = container.configLoader,
                        levelRepository = container.levelRepository,
                    )
                }
            }
        }
    }
}
