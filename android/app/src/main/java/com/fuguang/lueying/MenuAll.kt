package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 多久以前：刚刚 / 5分钟前 / 3小时前 / 昨天 / 4天前 / 10月3日。 */
internal fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "${m}分钟前"
        m < 24 * 60 -> "${m / 60}小时前"
        m < 48 * 60 -> "昨天"
        m < 7 * 24 * 60 -> "${m / (24 * 60)}天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(t))
    }
}

/** 字里命中的词标成杏色。 */
private val pickClose = Ic("M6 6l12 12M18 6L6 18")
private val pickTrash = Ic("M4.5 7h15M9.5 7V5h5v2M6.5 7l1 12.5h9l1-12.5M10 11v5M14 11v5")

private fun marked(text: String, q: String): AnnotatedString = buildAnnotatedString {
    if (q.isEmpty()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (true) {
        val k = text.indexOf(q, i, ignoreCase = true)
        if (k < 0) { append(text.substring(i)); break }
        append(text.substring(i, k))
        pushStyle(SpanStyle(color = SideWarm)); append(text.substring(k, k + q.length)); pop()
        i = k + q.length
    }
}

/** 「查看全部」：所有对话，按时间排；上面一个搜索框，标题和聊天内容都能搜。 */
/**
 * 全部对话。长按一条进入勾选（第 562 版，原型 LHXbC3gKDhR1L5HGLQcZSW）：每行左边的图标原地换成方框，长按的那条先勾上；
 * 顶栏原地换成「× · 已选 n 项 · 全选 · 删除」。删除不先问，底下「删掉了 n 段对话 · 撤销」4 秒（ConvMenuLayer 那条，点空白就收）。
 * 最后一条被取消勾选、点 ×、按返回都退出勾选。侧栏里那几条最近对话的长按还是原来的小菜单（重命名、置顶、分享在那儿）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllConvs(history: ConvStore, current: String?, busy: Boolean, onBack: () -> Unit, onOpen: (Conv) -> Unit) {
    var q by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selecting by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun leave() { selecting = false; picked = emptySet() }
    fun toggle(id: String) { picked = if (id in picked) picked - id else picked + id; if (picked.isEmpty()) selecting = false }
    androidx.activity.compose.BackHandler(enabled = selecting) { leave() }
    // 默认只看现在这位助理的；点「全部助理」看所有人的（点开别人的对话会换到她那边）
    var everyone by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val key = q.trim()
    val rows = remember(key, everyone, Memory.activeId, history.all.size, history.all.firstOrNull()?.updated) {
        (if (everyone) history.visible() else history.of()).sortedByDescending { it.updated }.mapNotNull { c ->
            if (key.isEmpty() || c.title.contains(key, true)) c to ""
            else c.msgs.firstOrNull { it.text.contains(key, true) }?.let { m ->
                // 内容里命中：带出命中处前后一小段
                val k = m.text.indexOf(key, ignoreCase = true)
                c to m.text.substring((k - 8).coerceAtLeast(0), (k + key.length + 16).coerceAtMost(m.text.length)).replace('\n', ' ')
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        val shownIds = rows.map { it.first.id }.distinct()
        AnimatedContent(selecting, transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(140)) }, label = "allHead") { sel ->
            if (!sel) Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Pressable(onBack, Modifier.align(Alignment.CenterStart), label = "返回") { p ->
                    SvgIcon(SideIcons.back, if (p) Page.muted else Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.padding(8.dp))
                }
                Txt("全部对话", Page.ink, 17.sp, Modifier.align(Alignment.Center), spacing = ls(3f, 17f), family = Serif, maxLines = 1)
            } else Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pressable({ leave() }, label = "退出选择") { p ->
                    SvgIcon(pickClose, if (p) Page.muted else Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.padding(8.dp))
                }
                Txt("已选 ${picked.size} 项", Page.ink, 15.5.sp, Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
                val allOn = shownIds.isNotEmpty() && picked.containsAll(shownIds)
                PressSurface(
                    { if (allOn) leave() else picked = picked + shownIds }, Modifier.height(32.dp),
                    fill = if (allOn) Page.ink else veil(.08f), pressedFill = if (allOn) Page.ink.copy(alpha = .85f) else veil(.14f), pressScale = .95f, label = "全选",
                ) { Txt("全选", if (allOn) Skin.onAccent else Page.ink, 13.sp, Modifier.padding(horizontal = 13.dp), maxLines = 1) }
                Pressable({
                    // 不先问：删掉，底下留 4 秒撤销
                    val gone = history.all.filter { it.id in picked }
                    if (gone.isNotEmpty()) {
                        val cur = gone.any { it.id == current }
                        gone.forEach { history.delete(it.id) }
                        if (cur) Chats.fresh()
                        ConvMenu.goneCurrent = if (cur) current else null
                        ConvMenu.gone = gone
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                    leave()
                }, Modifier.padding(start = 4.dp), enabled = picked.isNotEmpty(), label = "删除") { p ->
                    SvgIcon(pickTrash, Skin.danger.copy(alpha = if (p) .6f else 1f), 21.dp, stroke = 1.6f, modifier = Modifier.padding(8.dp))
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().background(veil(.06f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(SideIcons.search, Page.muted, 18.dp, stroke = 1.5f)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = TextStyle(fontFamily = Sans, color = Page.ink, fontSize = 14.5.sp), cursorBrush = SolidColor(SideWarm),
                decorationBox = { inner -> Box { if (q.isEmpty()) Txt("搜索标题和内容", Page.muted, 14.5.sp); inner() } },
            )
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            ScopeChip(!everyone, "只看 ${Memory.active.name}", { PersonaDot(Memory.active, 16.dp) }) { everyone = false }
            ScopeChip(everyone, "全部助理") { everyone = true }
        }
        if (rows.isEmpty()) {
            Txt(if (key.isEmpty()) "还没有对话" else "没有找到「$key」", SideFaint, 13.5.sp, Modifier.fillMaxWidth().padding(top = 36.dp).padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp)) {
            items(rows.distinctBy { it.first.id }, key = { it.first.id }) { (c, hit) ->
                val on = c.id == current
                val src = remember { MutableInteractionSource() }
                val p by src.collectIsPressedAsState()
                val ticked = c.id in picked
                val boxed by animateFloatAsState(if (selecting) 1f else 0f, tween(200, easing = Curves.standard), label = "pickBox")
                val tick by animateFloatAsState(if (ticked) 1f else 0f, tween(180, easing = Curves.standard), label = "pickTick")
                Box(
                    Modifier.fillMaxWidth().combinedClickable(
                        src, null, onClickLabel = c.title,
                        onClick = { if (selecting) toggle(c.id) else onOpen(c) },
                        onLongClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            if (selecting) toggle(c.id) else { selecting = true; picked = setOf(c.id) }
                        },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (ticked) SideWarm.copy(alpha = .13f) else if (on && !selecting) SideWarm.copy(alpha = .13f) else if (p) veil(.07f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        val owner = if (everyone) Memory.get(c.who) else null
                        Box(Modifier.padding(top = 1.dp).size(20.dp), contentAlignment = Alignment.Center) {
                            // 平时是图标（或那位助理的头像），勾选时原地换成方框
                            Box(Modifier.graphicsLayer { alpha = 1f - boxed; val z = 1f - .4f * boxed; scaleX = z; scaleY = z }) {
                                if (owner != null) PersonaDot(owner, 19.dp)
                                else SvgIcon(convIcon(c), if (on) SideWarm else SideFaint, 17.dp, stroke = 1.4f)
                            }
                            if (boxed > 0f) {
                                val edge = SideFaint; val fillC = SideWarm; val inkC = Skin.onAccent
                                androidx.compose.foundation.Canvas(Modifier.size(19.dp).graphicsLayer { alpha = boxed; val z = .6f + .4f * boxed; scaleX = z; scaleY = z }) {
                                    val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                                    val w = 1.5.dp.toPx()
                                    drawRoundRect(edge.copy(alpha = edge.alpha * (1f - tick)), androidx.compose.ui.geometry.Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(size.width - w, size.height - w), r, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                                    if (tick > 0f) {
                                        drawRoundRect(fillC.copy(alpha = tick), cornerRadius = r)
                                        val u = size.width / 19f
                                        val a = androidx.compose.ui.geometry.Offset(5f * u, 9.8f * u); val b = androidx.compose.ui.geometry.Offset(8.2f * u, 13f * u); val e = androidx.compose.ui.geometry.Offset(14f * u, 6.4f * u)
                                        val l1 = (b - a).getDistance(); val l2 = (e - b).getDistance(); val d = tick * (l1 + l2)
                                        val sw = 2.dp.toPx()
                                        drawLine(inkC, a, if (d < l1) a + (b - a) * (d / l1) else b, sw, androidx.compose.ui.graphics.StrokeCap.Round)
                                        if (d > l1) drawLine(inkC, b, b + (e - b) * ((d - l1) / l2), sw, androidx.compose.ui.graphics.StrokeCap.Round)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            androidx.compose.foundation.text.BasicText(
                                marked(c.title.ifBlank { "新对话" }, key), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Sans, color = SideInk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
                            )
                            val meta = ago(c.updated) + (if (Chats.isRunning(c.id)) " · 她在回答" else "")
                            androidx.compose.foundation.text.BasicText(
                                buildAnnotatedString { append(meta); if (hit.isNotEmpty()) { append(" · "); append(marked(hit, key)) } },
                                Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontFamily = Sans, color = SideFaint, fontSize = 12.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「查看全部」顶上的范围：只看这一位 / 全部助理。 */
@Composable
private fun ScopeChip(on: Boolean, text: String, lead: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick, label = text) { p ->
        Row(
            Modifier.background(if (on) SideWarm.copy(alpha = .16f) else veil(if (p) .09f else .05f), RoundedCornerShape(50))
                .padding(start = if (lead != null) 5.dp else 13.dp, end = 13.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) { lead(); Spacer(Modifier.width(6.dp)) }
            Txt(text, if (on) Page.ink else Page.muted, 12.5.sp, Modifier.widthIn(max = 150.dp), maxLines = 1)
        }
    }
}
