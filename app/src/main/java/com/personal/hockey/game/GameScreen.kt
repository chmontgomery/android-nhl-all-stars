package com.personal.hockey.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.personal.hockey.input.ControlsLayout
import com.personal.hockey.render.CLEAR_PAUSE
import com.personal.hockey.render.CLEAR_SCOREBOARD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val FINISH_HOLD_MS = 1500L

@Composable
fun GameScreen(
    vm: GameViewModel,
    soundOn: Boolean,
    vibrationOn: Boolean,
    onSoundChange: (Boolean) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onFinished: (homeScore: Int, awayScore: Int) -> Unit,
    onQuit: () -> Unit,
) {
    // Only what needs the HUD reads it: the scoreboard and banner recompose as the clock ticks, the
    // screen itself only when paused or finished changes.
    val hud = vm.hud.collectAsState()
    val paused by remember { derivedStateOf { hud.value.paused } }
    val finished by remember { derivedStateOf { hud.value.finished } }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val left = insets.getLeft(density, direction)
    val top = insets.getTop(density)
    val right = insets.getRight(density, direction)
    val bottom = insets.getBottom(density)
    var size by remember { mutableStateOf(IntSize.Zero) }

    // A new layout only when the numbers change: assigning one cancels any held input.
    val layout = remember(size, density.density, left, top, right, bottom) {
        if (size == IntSize.Zero) null
        else ControlsLayout(size.width.toFloat(), size.height.toFloat(), density.density,
            left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
    LaunchedEffect(layout) { layout?.let(vm::setLayout) }
    LaunchedEffect(soundOn) { vm.setSoundOn(soundOn) }
    LaunchedEffect(vibrationOn) { vm.setVibrationOn(vibrationOn) }
    LaunchedEffect(vm) { while (true) androidx.compose.runtime.withFrameNanos { vm.onFrame(it) } }

    val view = LocalView.current
    DisposableEffect(view, paused) {
        view.keepScreenOn = !paused
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.pause()
        vm.controls.cancelAll()
    }
    BackHandler { if (vm.hud.value.paused) vm.resume() else vm.pause() }

    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(finished) {
        if (finished) {
            delay(FINISH_HOLD_MS)
            val h = vm.hud.value
            currentOnFinished(h.homeScore, h.awayScore)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("game-canvas")
                .onSizeChanged { size = it }
                .systemGestureExclusion {
                    val r = layout?.gestureExclusion
                    if (r == null) Rect.Zero else Rect(r[0], r[1], r[2], r[3])
                }
                .pointerInput(vm) { forwardPointers(vm) },
        ) {
            vm.frame.value
            vm.renderer.draw(this, vm.match.sim, vm.timeS)
        }
        if (layout != null) GameControls(vm, layout, hud)

        // The goal banner lives in the clear band between the stick and the buttons, in screen coordinates.
        GoalBannerLayer(hud, layout)

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            HudLayer(vm, hud)
            Box(
                Modifier.align(Alignment.TopEnd).size(44.dp)
                    .onGloballyPositioned { vm.keepClear(CLEAR_PAUSE, it) }
                    .clip(CircleShape).background(Color(0x88000000))
                    .testTag("pause-button")
                    .clickable { vm.pause() },
                contentAlignment = Alignment.Center,
            ) { Text("II", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        if (paused) {
            PauseOverlay(soundOn, vibrationOn, onSoundChange, onVibrationChange, vm::resume, onQuit)
        }
    }
}

/** Vertical bias 0.6 puts the banner's centre at 0.8 of the height. */
private val GOAL_BANNER_ALIGNMENT = BiasAlignment(0f, 0.6f)

private const val GOAL_BANNER_START_SP = 40
private const val GOAL_BANNER_MIN_SP = 24
private const val GOAL_BANNER_STEP_SP = 2
private const val GOAL_BANNER_PAD_DP = 24
private const val GOAL_BANNER_MARGIN_DP = 16f
private const val GOAL_BANNER_SLACK_DP = 6

/** Reads the HUD so only this recomposes as the banner comes and goes. */
@Composable
private fun GoalBannerLayer(hud: State<HudState>, layout: ControlsLayout?) {
    val h = hud.value
    if (h.goalBanner) h.banner?.let { GoalBanner(it, layout) }
}

/**
 * The goal banner, in the clear band between the stick and the buttons (from [ControlsLayout]). The text
 * starts large and shrinks to a minimum until it fits on one line; below that it wraps to two lines.
 */
@Composable
private fun GoalBanner(text: String, layout: ControlsLayout?) {
    val density = LocalDensity.current.density
    // Left of the buttons (their left edge is the same with or without the puck) and right of the stick.
    val bandLeft = layout?.let { (it.stickRestX + it.stickRadius) / density + GOAL_BANNER_MARGIN_DP } ?: 0f
    val bandRight = layout?.let { l ->
        minOf(l.buttons(true).minOf { it.cx - it.radius }, l.buttons(false).minOf { it.cx - it.radius }) / density -
            GOAL_BANNER_MARGIN_DP
    }
    val endPad = if (layout == null || bandRight == null) 0f else ((layout.width / density) - bandRight).coerceAtLeast(0f)
    val measurer = rememberTextMeasurer()
    val innerPx = if (layout == null || bandRight == null) Int.MAX_VALUE
    else ((bandRight - bandLeft - 2 * GOAL_BANNER_PAD_DP - GOAL_BANNER_SLACK_DP) * density).toInt().coerceAtLeast(0)
    // Measured up front, once per banner, so there is no frame of clipped text while it shrinks.
    val sizeSp = remember(text, innerPx, density) {
        var sp = GOAL_BANNER_START_SP
        while (sp > GOAL_BANNER_MIN_SP && measurer.measure(
                text, TextStyle(fontSize = sp.sp, fontWeight = FontWeight.Bold), softWrap = false,
                maxLines = 1, constraints = Constraints(maxWidth = innerPx),
            ).didOverflowWidth
        ) sp = maxOf(GOAL_BANNER_MIN_SP, sp - GOAL_BANNER_STEP_SP)
        sp
    }
    val wrap = sizeSp <= GOAL_BANNER_MIN_SP
    Box(Modifier.fillMaxSize().padding(start = bandLeft.dp, end = endPad.dp)) {
        Text(
            text, color = Color.White, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, overflow = TextOverflow.Visible, softWrap = wrap, maxLines = if (wrap) 2 else 1,
            modifier = Modifier.align(GOAL_BANNER_ALIGNMENT).testTag("banner").clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC000000)).padding(horizontal = GOAL_BANNER_PAD_DP.dp, vertical = 6.dp),
        )
    }
}

/** The scoreboard and banner: the only parts that read the HUD every time it changes. */
@Composable
private fun HudLayer(vm: GameViewModel, hud: State<HudState>) {
    val h = hud.value
    Box(Modifier.fillMaxSize()) {
        Hud(
            h, Color(vm.setup.home.primary), Color(vm.setup.away.primary),
            Modifier.align(Alignment.TopStart).onGloballyPositioned { vm.keepClear(CLEAR_SCOREBOARD, it) },
        )
        // Goal banners are drawn by GoalBanner; the others stay centred.
        h.banner?.takeIf { !h.goalBanner }?.let {
            Text(
                it, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                // White on a dark plate: bare white text vanishes against the ice.
                modifier = Modifier.align(Alignment.Center)
                    .testTag("banner").clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000)).padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
    }
}

/** The touch controls; recomposes only when possession flips the button set. */
@Composable
private fun GameControls(vm: GameViewModel, layout: ControlsLayout, hud: State<HudState>) {
    val hasPuck by remember { derivedStateOf { hud.value.hasPuck } }
    ControlsOverlay(vm.controls, layout, hasPuck) { vm.frame.value }
}

/** Keeps the board text clear of the overlay at [coords]; the game canvas fills the root, so root bounds are canvas pixels. */
private fun GameViewModel.keepClear(slot: Int, coords: LayoutCoordinates) {
    val r = coords.boundsInRoot()
    keepBoardTextClear(slot, r.left, r.top, r.right, r.bottom)
}

/** Forwards every pointer change to the controls; a cancelled gesture releases everything. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.forwardPointers(vm: GameViewModel) {
    val controls = vm.controls
    awaitEachGesture {
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                for (change in event.changes) {
                    val id = change.id.value
                    when {
                        change.pressed && !change.previousPressed -> controls.down(id, change.position.x, change.position.y)
                        !change.pressed && change.previousPressed -> controls.up(id)
                        change.pressed -> controls.move(id, change.position.x, change.position.y)
                    }
                    change.consume()
                }
            } while (event.changes.any { it.pressed })
        } catch (e: CancellationException) {
            controls.cancelAll()
            throw e
        }
    }
}
