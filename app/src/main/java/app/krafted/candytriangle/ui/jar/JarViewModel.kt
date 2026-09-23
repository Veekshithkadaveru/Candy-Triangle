package app.krafted.candytriangle.ui.jar

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
import app.krafted.candytriangle.level.BallSkin
import app.krafted.candytriangle.level.JarConfig
import app.krafted.candytriangle.level.TrailType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for `CandyJarScreen` (PRD §7).
 *
 * ## Injection
 *
 * [progressStore] and [configLoader] arrive by constructor, not from a `Context`, which is what
 * makes every rule below reachable from §11's JVM-only suite — `JarViewModelTest` builds this with
 * `ProgressStore(FakePreferencesDataStore())` and a lambda-backed `ConfigLoader`, with no Android
 * context anywhere. [factory] is the only place that touches a `Context`, and it does so through
 * `Context.appContainer`.
 *
 * ## Unlock validation lives here
 *
 * `ProgressStore.equipBallSkin` and `equipTrail` deliberately do not validate — their KDoc hands
 * that job to D2, because re-validating there would need the jar thresholds from a config the data
 * layer does not depend on. [equipSkin] and [equipTrail] are therefore the gate: a locked cosmetic
 * is a silent no-op, never a write.
 *
 * ## Trails
 *
 * D2 unlocks, offers and persists a [TrailType], and that is the entirety of D2's trail scope.
 * **Nothing draws a trail** — the ball-trail particle system is D5's job. Equipping one today
 * changes a preference and the selector's highlight, and no pixel on the board.
 *
 * ## Sweet Rooms
 *
 * Tier 3 unlock state (level ids 101..104) is *displayed* only. Routing into a Sweet Room is D3/D4
 * map work, so this class exposes no navigation for it.
 */
class JarViewModel(
    private val progressStore: ProgressStore,
    private val configLoader: ConfigLoader,
) : ViewModel() {

    /**
     * `null` first, then the parsed §7 table.
     *
     * The leading `null` is not a loading spinner: the jar screen is immediately useful with §7's
     * shipped thresholds, so it renders them and quietly re-maps if `config.json` moved them.
     * `ConfigLoader.config()` caches and never throws — a missing or corrupt asset yields
     * `GameConfig.DEFAULTS`, so there is no error branch to model here.
     */
    private val jarConfig: Flow<JarConfig?> = flow {
        emit(null)
        emit(configLoader.config().jar)
    }

    /**
     * The whole screen as one value.
     *
     * `WhileSubscribed` rather than `Eagerly`: the only consumer is the composable, and there is
     * no reason to hold a DataStore collector open behind a backgrounded screen. Before anyone
     * subscribes the value is [INITIAL], which is mapped against §7's default thresholds — so a
     * first frame never shows the wrong ticks.
     */
    val uiState: StateFlow<CandyJarUiState> =
        combine(progressStore.progress, jarConfig) { progress, config ->
            JarUiStateMapper.map(
                jarCounts = progress.jarCounts,
                equippedBallSkin = progress.equippedBallSkin,
                equippedTrail = progress.equippedTrail,
                jarConfig = config,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), INITIAL)

    /**
     * Equips [skin] if a jar has earned it, else does nothing at all.
     *
     * The gate is re-derived from the store and the config rather than read off [uiState], so it
     * is correct even when nothing is subscribed (see `WhileSubscribed` above) and cannot be fooled
     * by a stale snapshot.
     */
    fun equipSkin(skin: BallSkin) {
        viewModelScope.launch {
            val config = configLoader.config().jar
            val counts = progressStore.jarCounts.first()
            if (skin !in JarUiStateMapper.unlockedSkins(counts, config)) return@launch
            progressStore.equipBallSkin(skin)
        }
    }

    /** Equips [trail] if a jar has earned it, else does nothing; see [equipSkin]. */
    fun equipTrail(trail: TrailType) {
        viewModelScope.launch {
            val config = configLoader.config().jar
            val counts = progressStore.jarCounts.first()
            if (trail !in JarUiStateMapper.unlockedTrails(counts, config)) return@launch
            progressStore.equipTrail(trail)
        }
    }

    companion object {

        /** Long enough to survive a rotation or a brief backgrounding without re-reading DataStore. */
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Four empty jars against §7's default thresholds — what the screen shows for one frame. */
        val INITIAL: CandyJarUiState = JarUiStateMapper.map(
            jarCounts = PlayerProgress.EMPTY_JARS,
            equippedBallSkin = BallSkin.DEFAULT,
            equippedTrail = TrailType.NONE,
            jarConfig = null,
        )

        /**
         * Pulls [ProgressStore] and [ConfigLoader] out of the app-scoped `AppContainer`.
         *
         * `Context.appContainer` casts `applicationContext` to `CandyTriangleApp`, so this must not
         * be called from a `@Preview` — the previews in this package all render stateless
         * composables directly for exactly that reason.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val container = context.applicationContext.appContainer
            return viewModelFactory {
                initializer { JarViewModel(container.progressStore, container.configLoader) }
            }
        }
    }
}
