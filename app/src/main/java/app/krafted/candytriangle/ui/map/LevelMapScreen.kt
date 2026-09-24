package app.krafted.candytriangle.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.ui.intro.LevelIntroDialog
import app.krafted.candytriangle.ui.intro.LevelIntroUiState
import app.krafted.candytriangle.ui.theme.CandyGold
import app.krafted.candytriangle.ui.theme.CandyTriangleTheme
import app.krafted.candytriangle.ui.theme.IcingWhite
import app.krafted.candytriangle.ui.theme.NightVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// Screen-chrome tints, private to this file: ui/theme/Color.kt is shared.
private val MapTopScrim = Color(0xE60B0518)
private val MapChipFill = Color(0x99140A24)
private val MapHintFill = Color(0xF22A1148)

/** How long a locked-tap hint stays up. */
private const val HINT_MILLIS = 2_400L

/** Top-bar buttons a little tighter than Material's default, so the title fits a 360 dp phone. */
private val CompactButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * The level map (D3): the 4320 x 1920 panorama as four lazily loaded world slices in a horizontal
 * `LazyRow` (§6.1, §13), 40 level nodes with their crowns, the four Sweet Rooms, the §6.2 world
 * gates and the pulsing current-level marker. A tap on a playable node opens `LevelIntroDialog`.
 *
 * FROZEN SIGNATURE (D3 lead's skeleton) — `CandyNavHost` calls exactly this. [onPlayLevel] is
 * handed the level id once the player presses Play in the intro.
 *
 * Only the `ViewModel` lookup lives here. Everything drawn is [LevelMapContent], which the
 * previews render with a hand-built state and no `AppContainer`.
 *
 * Owner: D3 Agent B (map UI).
 */
@Composable
fun LevelMapScreen(
    onBack: () -> Unit,
    onPlayLevel: (levelId: Int) -> Unit,
    onOpenJar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scoped to this navigation destination, which stays on the back stack while a level runs, so
    // the map (and its scroll position) is still there when the level pops back to it.
    val context = LocalContext.current
    val viewModel: LevelMapViewModel =
        viewModel(factory = remember(context) { LevelMapViewModel.factory(context) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val intro by viewModel.intro.collectAsStateWithLifecycle()

    LevelMapContent(
        state = state,
        intro = intro,
        onBack = onBack,
        onOpenJar = onOpenJar,
        onNodeTap = viewModel::openLevel,
        onPlay = { levelId ->
            // Closed before leaving: the ViewModel outlives the trip into the level, so an intro
            // left open would still be showing when the level pops back to the map.
            viewModel.dismissIntro()
            onPlayLevel(levelId)
        },
        onDismissIntro = viewModel::dismissIntro,
        modifier = modifier,
    )
}

/**
 * The stateless half: the panorama, the top bar, the locked-tap hint and the intro dialog.
 *
 * [onNodeTap] is only ever called for a node the state says is playable; `LevelMapViewModel.openLevel`
 * still re-derives the lock from the store. A tap on anything locked never reaches it — it shows a
 * hint instead, which is UI-local state and gone after [HINT_MILLIS].
 */
@Composable
internal fun LevelMapContent(
    state: LevelMapUiState,
    intro: LevelIntroUiState?,
    onBack: () -> Unit,
    onOpenJar: () -> Unit,
    onNodeTap: (levelId: Int) -> Unit,
    onPlay: (levelId: Int) -> Unit,
    onDismissIntro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    OpenOnCurrentLevel(state = state, listState = listState)

    // The text outlives `hintVisible` so the pill fades out instead of blinking; the sequence
    // restarts the timer when the same node is tapped twice.
    var hint by remember { mutableStateOf<MapHint?>(null) }
    var hintVisible by remember { mutableStateOf(false) }
    var hintSequence by remember { mutableIntStateOf(0) }
    val showHint: (MapHint) -> Unit = remember {
        { next ->
            hint = next
            hintVisible = true
            hintSequence++
        }
    }
    LaunchedEffect(hintSequence) {
        if (hintSequence == 0) return@LaunchedEffect
        delay(HINT_MILLIS)
        hintVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NightVoid),
    ) {
        // Every slice is exactly as tall as this box (the LazyRow fills it and each slice fills
        // the LazyRow's height), so the shared sprites are sized for that height once, here.
        val sprites = rememberMapSprites(
            sliceHeightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else 0,
        )

        LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items = state.slices, key = { _, slice -> slice.world }) { index, slice ->
                WorldSlice(
                    slice = slice,
                    exitLit = MapPathMath.exitLit(state, index),
                    sprites = sprites,
                    onNodeTap = onNodeTap,
                    onHint = showHint,
                    // The panorama at screen height; only the visible slices are ever composed.
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(MapLayout.SLICE_ASPECT),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapTopBar(
                totalCrowns = state.totalCrowns,
                maxCrowns = state.maxCrowns,
                coin = sprites.coin,
                onBack = onBack,
                onOpenJar = onOpenJar,
            )
            AnimatedVisibility(
                visible = hintVisible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut(),
            ) {
                hint?.let { HintPill(it) }
            }
        }
    }

    intro?.let { current ->
        LevelIntroDialog(
            state = current,
            onPlay = { onPlay(current.levelId) },
            onDismiss = onDismissIntro,
        )
    }
}

/**
 * Scrolls the map — once — so the current level's node is centred (`MapScrollMath.openingScroll`).
 *
 * "Once" is the design. It waits for [LevelMapUiState.loaded], so it never centres the
 * fresh-install placeholder's level 1. The flag is `rememberSaveable`, so coming back from a level
 * (this destination stays on the back stack) or from a process restore does not yank the map
 * away from where `LazyListState` restored it. A player who scrolled before progress loaded keeps
 * their own position.
 */
@Composable
private fun OpenOnCurrentLevel(state: LevelMapUiState, listState: LazyListState) {
    var opened by rememberSaveable { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.loaded) {
        if (!state.loaded || opened) return@LaunchedEffect
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            opened = true
            return@LaunchedEffect
        }
        // The slice and viewport widths as laid out, not as predicted.
        val layout = snapshotFlow { listState.layoutInfo }
            .first { it.viewportSize.width > 0 && it.visibleItemsInfo.isNotEmpty() }
        val target = MapScrollMath.openingScroll(
            state = latest,
            sliceWidthPx = layout.visibleItemsInfo.first().size,
            viewportWidthPx = layout.viewportSize.width,
        )
        listState.scrollToItem(target.index, target.offsetPx)
        opened = true
    }
}

/**
 * One world's slice: backdrop, path, gate, nodes, Sweet Room and banner — each positioned from the
 * state's `MapPoint`s and sized from `MapLayout`, through `MapGeometry`, in this slice's own pixels.
 * They come from the slice's constraints, so they are exactly what `aspectRatio` laid out.
 *
 * Composition order is paint order: backdrop (with the locked-world dim), path, gate, nodes, the
 * current-level marker, the Sweet Room — above the dim, so a jar-opened room on a shut world is
 * never greyed by it — and the banner.
 */
@Composable
private fun WorldSlice(
    slice: WorldSliceUiState,
    exitLit: Boolean,
    sprites: MapSprites,
    onNodeTap: (levelId: Int) -> Unit,
    onHint: (MapHint) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val tint = Color(slice.pegTintArgb)

        MapBackdrop(
            world = slice.world,
            widthPx = widthPx,
            heightPx = heightPx,
            locked = !slice.unlocked,
            modifier = Modifier.matchParentSize(),
        )
        MapPath(
            slice = slice,
            exitLit = exitLit,
            widthPx = widthPx,
            heightPx = heightPx,
            tint = tint,
            modifier = Modifier.matchParentSize(),
        )

        slice.gate?.let { gate ->
            val badge = MapGeometry.gate(gate.position, widthPx, heightPx)
            GateBadge(
                gate = gate,
                tint = tint,
                onClick = { MapHints.forGate(gate)?.let(onHint) },
                modifier = Modifier.placeAt(badge),
            )
            if (!gate.open) {
                val band = MapGeometry.gateLabel(badge, heightPx)
                GateRequirements(
                    gate = gate,
                    sprites = sprites,
                    heightPx = band.height,
                    modifier = Modifier.placeAt(band),
                )
            }
        }

        slice.levels.forEach { node ->
            key(node.levelId) {
                val rect = MapGeometry.levelNode(node.position, widthPx, heightPx)
                LevelNode(
                    node = node,
                    tint = tint,
                    diameterPx = rect.width,
                    onClick = {
                        if (node.isPlayable) {
                            onNodeTap(node.levelId)
                        } else {
                            MapHints.forLevel(slice, node)?.let(onHint)
                        }
                    },
                    modifier = Modifier.placeAt(rect),
                )
                CrownRow(
                    crowns = if (node.status == NodeStatus.CLEARED) node.crowns else 0,
                    sprites = sprites,
                    dimmed = !node.isPlayable,
                    modifier = Modifier.placeAt(MapGeometry.crownRow(rect, heightPx)),
                )
            }
        }

        // After every node, so no neighbour's glow is painted over it.
        slice.levels.firstOrNull { it.isCurrent }?.let { current ->
            val rect = MapGeometry.levelNode(current.position, widthPx, heightPx)
            CurrentLevelMarker(
                sprite = sprites.marker,
                tint = tint,
                modifier = Modifier.placeAt(MapGeometry.marker(rect, heightPx)),
            )
        }

        slice.sweetRoom?.let { room ->
            val rect = MapGeometry.sweetRoom(room.position, widthPx, heightPx)
            SweetRoomNode(
                room = room,
                diameterPx = rect.width,
                onClick = {
                    if (room.isPlayable) {
                        onNodeTap(room.levelId)
                    } else {
                        MapHints.forSweetRoom(room)?.let(onHint)
                    }
                },
                modifier = Modifier.placeAt(rect),
            )
            val band = MapGeometry.sweetRoomBand(rect, heightPx)
            if (room.status == NodeStatus.LOCKED) {
                JarProgressLabel(room = room, heightPx = band.height, modifier = Modifier.placeAt(band))
            } else {
                CrownRow(
                    crowns = if (room.status == NodeStatus.CLEARED) room.crowns else 0,
                    sprites = sprites,
                    modifier = Modifier.placeAt(band),
                )
            }
        }

        val banner = MapGeometry.banner(widthPx, heightPx)
        WorldBanner(
            slice = slice,
            tint = tint,
            coin = sprites.coin,
            heightPx = banner.height,
            modifier = Modifier.placeAt(banner),
        )
    }
}

/**
 * Back, the title, the §6.2 crown total and the Candy Jar, over the panorama.
 *
 * A gradient that fades to transparent rather than a surface, so the backdrop reads through it.
 * The activity is edge-to-edge and sticky-immersive, so — like `CandyJarScreen`'s
 * `systemBarsPadding` — it pads for bars that can reappear transiently, and for a camera cutout;
 * only the top and sides, since it sits at the top.
 */
@Composable
private fun MapTopBar(
    totalCrowns: Int,
    maxCrowns: Int,
    coin: ImageBitmap?,
    onBack: () -> Unit,
    onOpenJar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(MapTopScrim, Color.Transparent)))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, contentPadding = CompactButtonPadding) {
            Text(stringResource(R.string.action_back))
        }
        Text(
            text = stringResource(R.string.map_title),
            style = MaterialTheme.typography.titleMedium,
            color = IcingWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        CrownChip(totalCrowns = totalCrowns, maxCrowns = maxCrowns, coin = coin)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onOpenJar, contentPadding = CompactButtonPadding) {
            Text(stringResource(R.string.label_candy_jar))
        }
    }
}

/** Main-level crowns out of all of them — the number the §6.2 gates compare against. */
@Composable
private fun CrownChip(
    totalCrowns: Int,
    maxCrowns: Int,
    coin: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.map_cd_crowns_total, totalCrowns, maxCrowns)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MapChipFill)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinIcon(coin = coin, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.map_crowns_format, totalCrowns, maxCrowns),
            style = MaterialTheme.typography.labelLarge,
            color = CandyGold,
        )
    }
}

/** A locked tap's explanation, just under the top bar and clear of the finger that tapped. */
@Composable
private fun HintPill(hint: MapHint, modifier: Modifier = Modifier) {
    Text(
        text = hint.text(),
        style = MaterialTheme.typography.bodyMedium,
        color = IcingWhite,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MapHintFill)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // Announced without taking focus away from the node that was tapped.
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * Puts this element exactly on [rect]: sized to it and moved to its top-left corner in the slice.
 * Pixels end to end — every box comes out of `MapGeometry` in the slice's pixels, and a round trip
 * through dp could drift by one.
 */
private fun Modifier.placeAt(rect: PxRect): Modifier = this
    .offset { IntOffset(rect.left, rect.top) }
    .layout { measurable, _ ->
        val placeable = measurable.measure(
            Constraints.fixed(rect.width.coerceAtLeast(0), rect.height.coerceAtLeast(0)),
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** `MapHints`' decision as copy (`strings_map.xml`). */
@Composable
private fun MapHint.text(): String = when (this) {
    is MapHint.ClearPrevious -> stringResource(R.string.map_hint_clear_previous, levelId)

    is MapHint.OpenGate -> {
        val worldName = worldDisplayName(world)
        val level = levelToClear
        when {
            level != null && crownsShort > 0 -> pluralStringResource(
                R.plurals.map_hint_gate_level_and_crowns,
                crownsShort,
                level,
                crownsShort,
                worldName,
            )
            level != null -> stringResource(R.string.map_hint_gate_level, level, worldName)
            else -> pluralStringResource(R.plurals.map_hint_gate_crowns, crownsShort, crownsShort, worldName)
        }
    }

    is MapHint.FillJar -> pluralStringResource(
        R.plurals.map_hint_fill_jar,
        candiesShort,
        candiesShort,
        stringResource(jarColor.nameRes()),
        code,
    )
}

private fun CandyColor.nameRes(): Int = when (this) {
    CandyColor.GREEN -> R.string.candy_name_green
    CandyColor.PURPLE -> R.string.candy_name_purple
    CandyColor.PINK -> R.string.candy_name_pink
    CandyColor.BLUE -> R.string.candy_name_blue
}

// ---------------------------------------------------------------------------
// Design documentation — see the note in JarCanvas.kt on why these are previews and not tests.
// ---------------------------------------------------------------------------

@Preview(name = "Level map — fresh install", widthDp = 380, heightDp = 800, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun LevelMapFreshPreview() {
    CandyTriangleTheme {
        LevelMapContent(
            state = previewMapState(crowns = emptyMap(), openWorlds = 1, jarCounts = emptyMap()),
            intro = null,
            onBack = {},
            onOpenJar = {},
            onNodeTap = {},
            onPlay = {},
            onDismissIntro = {},
        )
    }
}

/**
 * Two slices wide. World 1 is cleared on 12 crowns, so World 2's gate is shut on its crown half
 * alone (12 / 15) and the marker sits on Level 10; the Purple jar has opened B2 on World 2's slice
 * all the same, while B1 still shows its Green jar's progress.
 */
@Preview(name = "Level map — shut gate, open Sweet Room", widthDp = 900, heightDp = 800, showBackground = true, backgroundColor = 0xFF0B0518)
@Composable
private fun LevelMapProgressPreview() {
    CandyTriangleTheme {
        LevelMapContent(
            state = previewMapState(
                crowns = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 1, 5 to 1, 6 to 2, 7 to 1, 8 to 1, 9 to 1, 10 to 1),
                openWorlds = 1,
                jarCounts = mapOf(CandyColor.GREEN to 143, CandyColor.PURPLE to 205),
            ),
            intro = null,
            onBack = {},
            onOpenJar = {},
            onNodeTap = {},
            onPlay = {},
            onDismissIntro = {},
        )
    }
}

/**
 * A hand-built map for the previews: four worlds of ten on a plain serpentine.
 *
 * Deliberately not `LevelMapViewModel.INITIAL`, `MapStateMapper` or `MapLayout.sliceLayout`: a
 * preview must render without the `AppContainer`, and must not lean on the logic it documents. The
 * lock rules here are a display-only simplification.
 *
 * @param crowns best crowns per main level; a level with any is cleared.
 * @param openWorlds worlds `1..openWorlds` are open, the rest shut.
 */
private fun previewMapState(
    crowns: Map<Int, Int>,
    openWorlds: Int,
    jarCounts: Map<CandyColor, Int>,
): LevelMapUiState {
    val total = crowns.values.sum()
    val frontier = (1..40).firstOrNull { (crowns[it] ?: 0) == 0 } ?: 41
    val frontierOpen = frontier <= 40 && (frontier - 1) / 10 + 1 <= openWorlds
    val current = if (frontierOpen) frontier else (frontier - 1).coerceAtLeast(1)
    val jars = listOf(CandyColor.GREEN, CandyColor.PURPLE, CandyColor.PINK, CandyColor.BLUE)
    val tints = listOf(0xFFFF4FC8, 0xFF3FE3FF, 0xFFA56BFF, 0xFFFFC23F)
    val gateCrowns = listOf(15, 35, 55)

    val slices = (1..4).map { world ->
        val first = (world - 1) * 10 + 1
        // Odd worlds climb and even ones descend, so each slice's exit meets the next one's entry.
        val climbing = world % 2 == 1
        val y = { i: Int -> if (climbing) 0.80f - i * 0.063f else 0.233f + i * 0.063f }
        val levels = (0 until 10).map { i ->
            val id = first + i
            val earned = crowns[id] ?: 0
            LevelNodeUiState(
                levelId = id,
                status = when {
                    earned > 0 -> NodeStatus.CLEARED
                    id == frontier && frontierOpen -> NodeStatus.AVAILABLE
                    else -> NodeStatus.LOCKED
                },
                crowns = earned,
                bestScore = earned * 5_000,
                isCurrent = id == current,
                position = MapPoint(if (i % 2 == 0) 0.27f else 0.73f, y(i)),
            )
        }
        val jar = jars[world - 1]
        val jarCount = jarCounts[jar] ?: 0
        WorldSliceUiState(
            world = world,
            levelFrom = first,
            levelTo = first + 9,
            pegTintArgb = tints[world - 1].toInt(),
            unlocked = world <= openWorlds,
            crownsEarned = levels.sumOf { it.crowns },
            crownsAvailable = 30,
            gate = if (world == 1) {
                null
            } else {
                GateUiState(
                    world = world,
                    requiresLevelCleared = first - 1,
                    levelCleared = (crowns[first - 1] ?: 0) > 0,
                    crownsRequired = gateCrowns[world - 2],
                    crownsHave = total,
                    open = world <= openWorlds,
                    position = MapPoint(0.5f, 0.52f),
                )
            },
            levels = levels,
            sweetRoom = SweetRoomNodeUiState(
                levelId = 100 + world,
                code = "B$world",
                status = if (jarCount >= 200) NodeStatus.AVAILABLE else NodeStatus.LOCKED,
                crowns = 0,
                bestScore = 0,
                jarColor = jar,
                jarCount = jarCount,
                jarThreshold = 200,
                position = MapPoint(0.5f, 0.30f),
            ),
            path = listOf(MapPoint(0f, y(0))) + levels.map { it.position } + MapPoint(1f, y(9)),
        )
    }
    return LevelMapUiState(
        slices = slices,
        totalCrowns = total,
        maxCrowns = 120,
        currentLevelId = current,
        currentSliceIndex = (current - 1) / 10,
    )
}
