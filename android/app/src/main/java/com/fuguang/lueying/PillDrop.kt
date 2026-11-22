package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 点长条以外的空白：长条收走。App 根上只看不拦地喂（[watch]，和 Zen、壁纸触摸同一个地方），
 * 所以底下的东西照常能点；只认「点一下」，滑动不算。
 */
internal object PillSpots {
    class Spot(val onOutside: () -> Unit) { var bounds: Rect = Rect.Zero; var on = false }

    val spots = ArrayList<Spot>()
    private var downAt: Offset? = null
    private var downT = 0L

    fun watch(e: PointerEvent) {
        val c = e.changes.firstOrNull() ?: return
        when (e.type) {
            PointerEventType.Press -> { downAt = c.position; downT = c.uptimeMillis }
            PointerEventType.Move -> downAt?.let { d -> if ((c.position - d).getDistance() > 30f) downAt = null }
            PointerEventType.Release -> {
                val d = downAt; downAt = null
                if (d != null && c.uptimeMillis - downT < 400) spots.toList().forEach { s -> if (s.on && !s.bounds.contains(d)) s.onOutside() }
            }
        }
    }
}

/**
 * 顶上长条从出现到消失的一整套（照苹果的通知横幅，原型 BGyA7XCFTWV9WPrd4FZk1Q）：
 *  - 出现：从屏幕上沿外掉下来，弹簧就位；位置这一个值同时带着透明度和大小。落稳时轻震。
 *  - 停留：6 秒没碰自己收；手指按着、[busy]、[stay] 时不计。
 *  - 手指：按住整条收一点；上下跟手，往下有橡皮筋；松手过三分之一或甩得快就带着速度飞走，否则弹回。
 *  - 收走：加速往上（比进来快）；[into] 时向下收进聊天。
 *  - 换一条（[target] 变了）：旧的原地退后淡掉，新的落在它上面。
 *  - 点空白：收走（[outside] 为假或 [busy] 时不收）。
 * 抖音、网盘两种长条都用它，不要再各写一套进出场。
 */
@Composable
internal fun <K : Any> PillDrop(
    target: K?,
    into: Boolean = false,
    busy: Boolean = false,
    stay: Boolean = false,
    drag: Boolean = true,
    outside: Boolean = true,
    onDismiss: () -> Unit,
    content: @Composable (K) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val p = remember { Animatable(1f) }        // 0 就位，1 藏在上沿外；小于 0 是被往下拉
    val hand = remember { Animatable(0f) }     // 向下收进聊天
    val press = remember { Animatable(0f) }
    val gone = remember { Animatable(0f) }     // 被顶替的那条在淡掉
    var cur by remember { mutableStateOf<K?>(null) }
    var old by remember { mutableStateOf<K?>(null) }
    var held by remember { mutableStateOf(false) }
    var dragP by remember { mutableStateOf<Float?>(null) }
    var raw by remember { mutableStateOf(0f) }
    var fling by remember { mutableStateOf(0f) }
    var h by remember { mutableStateOf(0) }
    val lift = with(density) { 84.dp.toPx() }
    val sink = with(density) { 64.dp.toPx() }
    val band = with(density) { 110.dp.toPx() }
    val fast = with(density) { 500.dp.toPx() }
    val dismiss by rememberUpdatedState(onDismiss)
    val isBusy by rememberUpdatedState(busy)
    val canOutside by rememberUpdatedState(outside)
    val want by rememberUpdatedState(target)

    val spot = remember { PillSpots.Spot { if (!isBusy && canOutside) dismiss() } }
    DisposableEffect(Unit) { PillSpots.spots.add(spot); onDispose { PillSpots.spots.remove(spot) } }
    SideEffect { spot.on = target != null && cur != null }

    LaunchedEffect(target) {
        if (target != null) {
            if (cur != target) {
                if (cur != null && p.value < .5f) {
                    old = cur
                    scope.launch { gone.snapTo(0f); gone.animateTo(1f, tween(200, easing = Curves.accel)); old = null }
                }
                cur = target; dragP = null; p.snapTo(1f)
                scope.launch { delay(150); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            }
            hand.snapTo(0f)
            p.animateTo(0f, spring(.78f, 420f))
        } else if (cur != null) {
            val v = fling; fling = 0f
            if (into) hand.animateTo(1f, tween(220, easing = Curves.accel))
            else if (v > 0f) p.animateTo(1f, spring(1f, 300f), initialVelocity = v)
            else p.animateTo(1f, tween(240, easing = Curves.accel))
            cur = null; p.snapTo(1f); hand.snapTo(0f)
        }
    }
    LaunchedEffect(target, held, busy, stay) {
        if (target != null && !held && !busy && !stay) { delay(6000); dismiss() }
    }

    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.TopCenter) {
        old?.let { k ->
            Box(Modifier.graphicsLayer { val g = gone.value; alpha = 1f - g; scaleX = 1f - .06f * g; scaleY = 1f - .06f * g; transformOrigin = TransformOrigin(.5f, 0f) }) { key(k) { content(k) } }
        }
        cur?.let { k ->
            Box(
                Modifier
                    .onGloballyPositioned { spot.bounds = it.boundsInRoot() }
                    .onSizeChanged { h = it.height }
                    .graphicsLayer {
                        val v = dragP ?: p.value
                        val q = v.coerceIn(0f, 1f)
                        val hd = hand.value
                        val s = (1f - .06f * q) * (1f - .015f * press.value) * (1f - .1f * hd)
                        translationY = -v * (size.height + lift) + sink * hd
                        alpha = ((1f - q) * 1.7f).coerceIn(0f, 1f) * (1f - hd)
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(.5f, 0f)
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            held = true
                            scope.launch { press.animateTo(1f, tween(80)) }
                            try {
                                do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { c -> c.pressed })
                            } finally {
                                held = false
                                scope.launch { press.animateTo(0f, tween(180)) }
                            }
                        }
                    }
                    .draggable(
                        rememberDraggableState { d ->
                            raw += d
                            val y = if (raw <= 0f) raw else band * (1f - 1f / (raw * .55f / band + 1f))
                            dragP = -y / (h + lift)
                        },
                        Orientation.Vertical,
                        enabled = drag && target != null,
                        onDragStarted = { raw = -p.value.coerceAtLeast(0f) * (h + lift); dragP = p.value },
                        onDragStopped = { vel ->
                            val dist = h + lift
                            val at = dragP ?: p.value
                            p.snapTo(at); dragP = null
                            if (want == null) { p.animateTo(1f, tween(200, easing = Curves.accel)); cur = null }
                            else if (!isBusy && (at * dist > h * .3f || vel < -fast)) { fling = if (vel < -fast * .6f) -vel / dist else 0f; dismiss() }
                            else p.animateTo(0f, spring(.78f, 420f), initialVelocity = -vel / dist)
                        },
                    ),
            ) { key(k) { content(k) } }
        }
    }
}
