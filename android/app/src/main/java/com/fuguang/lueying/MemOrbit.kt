package com.fuguang.lueying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 记忆页的搜索和排法（第 562 版，原型 PVDVB5D3n76dJJpv6eyCfr）。
 * 搜索是「精准加相近」：你打的字一字不差出现的排前面（暖色底标出来）；没有一字不差、但这几个字按顺序都出现了的
 * （中间可以隔着别的字）算相近，排后面（只把字变成暖色）。空格隔开的几个词要都对得上。只打一个字时不做相近。
 * 搜的范围：标题、正文、类别、日期。不认同义词（那要问模型，每搜一次都花钱）。
 */
internal object MemFind {
    /** 搜索框里的字。离开记忆页时清掉。 */
    var q by mutableStateOf("")
    /** true = 平铺（原来的格子），false = 收束成弧（默认）。记在 prefs `mem-flat`。 */
    var flat by mutableStateOf(false)

    val terms: List<String> get() = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun all(m: Mem) = (m.text + "\n" + MemTree.body(m) + "\n" + MemTree.kindZh(m.kind) + "\n" + MemTree.date(m)).lowercase()
    private fun near(w: String, all: String): Boolean {
        var i = 0
        for (c in all) { if (i < w.length && c == w[i]) i++ }
        return i == w.length
    }

    /** 2 一字不差，1 相近，0 没对上。没在搜时都算 2。 */
    fun rank(m: Mem, terms: List<String>): Int {
        if (terms.isEmpty()) return 2
        val a = all(m)
        val ts = terms.map { it.lowercase() }
        if (ts.all { a.contains(it) }) return 2
        return if (ts.all { a.contains(it) || (it.length > 1 && near(it, a)) }) 1 else 0
    }

    /** 在已经铺好字的 [b] 上把命中的标出来：一字不差的整段加暖色底，相近的只把那几个字变暖色。 */
    fun mark(b: AnnotatedString.Builder, text: String, terms: List<String>, exact: Boolean, hit: Color) {
        if (terms.isEmpty() || text.isEmpty()) return
        val low = text.lowercase()
        if (low.length != text.length) return
        if (exact) terms.forEach { t ->
            val w = t.lowercase()
            var i = low.indexOf(w)
            while (i >= 0 && w.isNotEmpty()) { b.addStyle(SpanStyle(background = hit.copy(alpha = .28f)), i, i + w.length); i = low.indexOf(w, i + w.length) }
        } else {
            val set = terms.joinToString("").lowercase().toSet()
            low.forEachIndexed { i, c -> if (c in set) b.addStyle(SpanStyle(color = hit), i, i + 1) }
        }
    }

    fun marked(text: String, m: Mem): AnnotatedString {
        val t = terms
        return AnnotatedString.Builder(text).apply { mark(this, text, t, rank(m, t) == 2, Skin.accent) }.toAnnotatedString()
    }
}

private val findIc = Ic("M10.5 4.5a6 6 0 1 0 0 12 6 6 0 0 0 0-12ZM15 15l4.5 4.5")
private val clearIc = Ic("M7 7l10 10M17 7L7 17")

/** 搜索框：左边放大镜，右边报「2 条 · 相近 3」，有字时出 ×。 */
@Composable
internal fun MemFindBar(hits: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).background(veil(.07f), RoundedCornerShape(15.dp)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SvgIcon(findIc, Page.muted, 18.dp, stroke = 1.7f)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            MemFind.q, { MemFind.q = it.replace("\n", "").take(40) },
            Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(color = Page.ink, fontSize = 15.sp, fontFamily = Sans),
            cursorBrush = SolidColor(Skin.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (MemFind.q.isEmpty()) Txt("搜记忆", Page.muted.copy(alpha = .6f), 15.sp, maxLines = 1)
                    inner()
                }
            },
        )
        if (hits.isNotEmpty()) Txt(hits, Page.muted, 12.sp, Modifier.padding(horizontal = 6.dp), maxLines = 1)
        if (MemFind.q.isNotEmpty()) Pressable({ MemFind.q = "" }, label = "清空") { pr ->
            SvgIcon(clearIc, if (pr) Page.muted else Page.ink.copy(alpha = .8f), 16.dp, stroke = 1.7f, modifier = Modifier.padding(10.dp))
        }
    }
}

/** 标题栏右边的「收束 / 平铺」：选中的那格实心。 */
@Composable
internal fun MemModeSwitch(onFlat: (Boolean) -> Unit) {
    Row(Modifier.background(veil(.07f), RoundedCornerShape(50)).padding(3.dp)) {
        listOf(false to "收束", true to "平铺").forEach { (flat, name) ->
            val on = MemFind.flat == flat
            PressSurface(
                { if (!on) onFlat(flat) }, Modifier.height(30.dp),
                fill = if (on) Skin.accent else Color.Transparent, pressedFill = if (on) Skin.accent else veil(.08f), pressScale = .95f, label = name,
            ) { Txt(name, if (on) Skin.onAccent else Page.muted, 12.5.sp, Modifier.padding(horizontal = 13.dp), maxLines = 1) }
        }
    }
}

/**
 * 一组记忆收成一道弧：卡片排在一个很大的圆上，圆心在卡片下方很远处，看到的是圆顶那一段。
 * 正中一张是正的、最亮，标题写在弧下面；两边的顺着弧歪开、下沉、变暗。左右拨整道弧跟手转，
 * 松手带着速度吸到最近的一张（弹簧 soft：k 180 · c 24），到头有阻尼。点旁边的卡它转到正中；
 * 点正中的打开；长按正中的出菜单。有头有尾，不做成能一直转的环（记忆按时间排，转一圈回到开头容易迷路）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MemOrbit(p: Persona, name: String, items: List<Mem>, onOpen: (Mem) -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val n = items.size
    val side = 150.dp
    val radius = 640.dp
    val step = 14.2f                                   // 相邻两张差多少度
    val arc = with(density) { radius.toPx() } * step * Math.PI.toFloat() / 180f   // 转一张，手指走多少像素
    val pos = remember { Animatable(0f) }              // 转到第几张（小数）
    var dragAt by remember { mutableStateOf<Float?>(null) }
    var raw by remember { mutableStateOf(0f) }
    val at = dragAt ?: pos.value
    val center = at.roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0))
    val soft = spring<Float>(dampingRatio = .894f, stiffness = 180f)

    // 搜索把这一组筛短了：别停在已经不存在的位置上
    LaunchedEffect(n) { if (pos.value > n - 1) pos.snapTo((n - 1).coerceAtLeast(0).toFloat()) }
    // 换了正中那张：轻轻一下
    var last by remember { mutableStateOf(-1) }
    LaunchedEffect(center) { if (last >= 0 && last != center) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); last = center }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 2.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Txt(name, Page.ink, 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Txt("${center + 1} / $n", Page.muted, 12.sp, maxLines = 1)
        }
        Box(
            Modifier.fillMaxWidth().height(238.dp).clipToBounds()
                .draggable(
                    rememberDraggableState { d ->
                        raw -= d / arc
                        val hi = (n - 1).coerceAtLeast(0).toFloat()
                        fun band(x: Float) = .9f * (1f - 1f / (x * .55f / .9f + 1f))
                        dragAt = when { raw < 0f -> -band(-raw); raw > hi -> hi + band(raw - hi); else -> raw }
                    },
                    Orientation.Horizontal,
                    onDragStarted = { raw = pos.value; dragAt = pos.value },
                    onDragStopped = { vel ->
                        val from = dragAt ?: pos.value
                        pos.snapTo(from); dragAt = null
                        val v = -vel / arc
                        val to = (from + v * .22f).roundToInt().coerceIn(0, (n - 1).coerceAtLeast(0))
                        pos.animateTo(to.toFloat(), soft, initialVelocity = v)
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            items.forEachIndexed { i, m ->
                val d = i - at
                val far = abs(d)
                if (far <= 4.2f) androidx.compose.runtime.key(m.id) {
                    val isCenter = i == center && far < .5f
                    val held = MemMenu.held == m.id
                    val k by animateFloatAsState(if (held) 1f else 0f, tween(if (held) 60 else 220), label = "orbitHeld")
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    val src = remember { MutableInteractionSource() }
                    Box(
                        Modifier.padding(top = 16.dp).size(side).zIndex(100f - far * 10f)
                            .graphicsLayer {
                                rotationZ = d * step
                                transformOrigin = TransformOrigin(.5f, radius.toPx() / size.height)
                                alpha = 1f - far.coerceAtMost(3f) * .16f
                            }
                            .onGloballyPositioned { if (isCenter) bounds = it.boundsInRoot() }
                            .combinedClickable(
                                src, null, onClickLabel = if (isCenter) "打开" else "转到这张", onLongClickLabel = "菜单",
                                onClick = { if (isCenter) onOpen(m) else scope.launch { pos.animateTo(i.toFloat(), soft) } },
                                onLongClick = {
                                    if (isCenter) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); MemMenu.open(m, bounds) }
                                    else scope.launch { pos.animateTo(i.toFloat(), soft) }
                                },
                            ),
                    ) { NotePaper(m, k, Modifier.fillMaxSize(), lines = 5) }
                }
            }
        }
        // 正中那张的标题和「类别 · 日期」
        val m = items.getOrNull(center)
        Column(Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (m != null) {
                androidx.compose.foundation.text.BasicText(
                    MemFind.marked(Memory.ownWords(m.text), m), Modifier.fillMaxWidth(),
                    style = TextStyle(color = Page.ink, fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sans, textAlign = TextAlign.Center),
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Txt(
                    listOfNotNull(if (m.pin) "常驻" else null, if (m.span) "${MemTree.kids(p, m).size} 份" else null, MemTree.kindZh(m.kind), MemTree.date(m)).joinToString(" · "),
                    if (m.pin) Skin.accent else Page.muted, 11.5.sp, Modifier.fillMaxWidth().padding(top = 3.dp), align = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}
