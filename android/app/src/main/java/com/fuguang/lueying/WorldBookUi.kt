package com.fuguang.lueying

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 世界书页面（照 Kelivo features/world_book/pages/world_book_page.dart、widgets/world_book_entry_widgets.dart、
 * widgets/world_book_entry_actions.dart）：一页里每本书一段，点书名收起 / 展开；长按书名拖动排书，拖条目左边的把手排条目；
 * 点条目编辑，长按条目弹操作；多选后复制 / 移动到另一本。拖动用 Calvin-LL/Reorderable。
 */
@Composable
internal fun WorldBookPage(screen: String, nav: MineNav) {
    // 原来点进一本书是单独一页（worldbook/<id>），现在都在这一页
    LaunchedEffect(screen) { if (screen != "worldbook") nav.screen = "worldbook" }
    val sheet = remember { KSheetState() }
    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize()) { BookList(nav, sheet) }
        KSheetHost(sheet)
    }
}

/** Kelivo 用的 Lucide 图标（24 格）。 */
private object WbI {
    val cloud = Ic("M12 13v8l-4-4M12 21l4-4M4.393 15.269A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.436 8.284")
    val plus = Ic("M5 12h14M12 5v14")
    val chev = Ic("M9 18l6-6-6-6")
    val checks = Ic("M3 17l2 2 4-4M3 7l2 2 4-4M13 6h8M13 12h8M13 18h8")
    val share = Ic("M8.59 13.51l6.83 3.98M15.41 6.51l-6.82 3.98", listOf(floatArrayOf(18f, 5f, 3f), floatArrayOf(6f, 12f, 3f), floatArrayOf(18f, 19f, 3f)))
    val cfg = Ic("M20 7h-9M14 17H5", listOf(floatArrayOf(17f, 17f, 3f), floatArrayOf(7f, 7f, 3f)))
    val trash = Ic("M3 6h18M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2M10 11v6M14 11v6")
    val grip = Ic("M9 5h.01M9 12h.01M9 19h.01M15 5h.01M15 12h.01M15 19h.01")
    val copy = Ic("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2ZM4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val move = Ic("M2 9V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H20a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-1M2 13h10M9 16l3-3-3-3")
    val x = Ic("M18 6 6 18M6 6l12 12")
    val bookOpen = Ic("M12 7v14M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z")
    val tree = Ic("M21 12h-8M21 6H8M21 18h-8M3 6v4c0 1.1.9 2 2 2h3M3 10v6c0 1.1.9 2 2 2h3")
}

private val outCubic = CubicBezierEasing(.33f, 1f, .68f, 1f)
private val inOutCubic = CubicBezierEasing(.65f, 0f, .35f, 1f)

/** 注入位置的颜色（Kelivo worldBookPositionColor，深色 / 浅色各一套）。 */
private fun posColor(p: WbPosition): Color = if (Skin.light) when (p) {
    WbPosition.BEFORE_SYSTEM -> Color(0xFF7052C2); WbPosition.AFTER_SYSTEM -> Color(0xFF2864BA); WbPosition.TOP -> Color(0xFF227C69)
    WbPosition.BOTTOM -> Color(0xFF9A5B13); WbPosition.DEPTH -> Color(0xFFB33A65)
} else when (p) {
    WbPosition.BEFORE_SYSTEM -> Color(0xFFB6A0FF); WbPosition.AFTER_SYSTEM -> Color(0xFF82B6FF); WbPosition.TOP -> Color(0xFF70CFB8)
    WbPosition.BOTTOM -> Color(0xFFFFBD76); WbPosition.DEPTH -> Color(0xFFFF9CB9)
}

private fun bookName(b: WorldBook) = b.name.trim().ifBlank { "未命名世界书" }
private fun entryName(e: WbEntry) = e.name.trim().ifBlank { "未命名条目" }

/** 顶上和书名右边的小图标按钮：按下缩一点。 */
@Composable
private fun HeadIcon(icon: Ic, label: String, color: Color = K.ink, enabled: Boolean = true, size: Int = 19, onClick: () -> Unit) {
    Pressable(onClick, Modifier.size(width = 36.dp, height = 40.dp), enabled = enabled, label = label) { pressed ->
        val sc by animateFloatAsState(if (pressed) .88f else 1f, tween(if (pressed) 70 else 180, easing = K.std), label = "hi")
        SvgIcon(icon, color, size.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { scaleX = sc; scaleY = sc })
    }
}

@Composable
private fun ColumnScope.BookList(nav: MineNav, sheet: KSheetState) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var toast by remember { mutableStateOf("") }
    /** 正在多选的书 → 选中的条目 id。 */
    val sel = remember { mutableStateMapOf<String, Set<String>>() }
    var exporting by remember { mutableStateOf<WorldBook?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u == null) return@rememberLauncherForActivityResult
        val raw = runCatching { ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        toast = when {
            raw.isNullOrBlank() -> "文件内容为空"
            else -> WorldBooks.import(raw, u.lastPathSegment ?: "世界书")?.let { r ->
                WorldBooks.put(r.book)
                if (r.unsupported > 0) "导入完成。${r.unsupported} 个条目使用了暂不支持的酒馆功能（如宏、正则、时效、筛选或插入规则），已保留内容并停用，请检查后再启用。" else "导入完成"
            } ?: "JSON 解析失败"
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u: Uri? ->
        val b = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (u == null) return@rememberLauncherForActivityResult
        toast = runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(WorldBooks.export(b).toByteArray()) }; "已导出为 ${b.name.ifBlank { "world_book" }}.json" }
            .getOrElse { "导出失败：${it.message}" }
    }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(if (toast.length > 20) 6000 else 1800); toast = "" } }
    // 多选中按返回：先退出多选（Kelivo SelectionBackScope）
    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }

    Title("世界书", false, onBack = { nav.up("mine") }) {
        Row {
            HeadIcon(WbI.cloud, "导入", size = 22) { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            HeadIcon(WbI.plus, "添加世界书", size = 22) { sheet.open { BookSheet(null, sheet) } }
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (WorldBooks.books.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SvgIcon(WbI.bookOpen, K.ink.copy(alpha = .3f), 64.dp, stroke = 1.4f)
                Spacer(Modifier.height(16.dp))
                Txt("暂无世界书", K.ink.copy(alpha = .6f), 14.sp)
            }
        } else {
            val listState = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                WorldBooks.moveBook(from.index, to.index)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            // 从聊天里的世界书卡点进来：展开、滚到这本，亮的条目 1.6 秒后淡掉
            LaunchedEffect(WorldBooks.focus) {
                val id = WorldBooks.focus ?: return@LaunchedEffect
                WorldBooks.expand(id)
                val i = WorldBooks.books.indexOfFirst { it.id == id }
                if (i >= 0) listState.animateScrollToItem(i)
                WorldBooks.focus = null
                delay(1600)
                WorldBooks.lit = emptySet()
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(WorldBooks.books, key = { it.id }) { b ->
                    ReorderableItem(reorder, key = b.id) { dragging ->
                        // 拖起来那本：从 .985 放到 1（Kelivo proxyDecorator，无阴影）
                        val sc by animateFloatAsState(if (dragging) 1f else .985f, tween(200, easing = outCubic), label = "bookDrag")
                        val canDrag = WorldBooks.books.size > 1 && b.id !in sel
                        val handle = Modifier.longPressDraggableHandle(
                            enabled = canDrag,
                            onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = { WorldBooks.save() },
                        )
                        Box(Modifier.graphicsLayer { if (dragging) { scaleX = sc; scaleY = sc } }) {
                            BookSection(b, handle, sel, sheet, onToast = { toast = it }, onExport = { exporting = b; exporter.launch("${b.name.ifBlank { "world_book" }}.json") })
                        }
                    }
                }
            }
        }
        // 底部提示
        WbToast(toast, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** 底部提示：从下面滑上来，过一会儿自己退下去。单独一个函数，免得在 Column 里挑错 AnimatedVisibility。 */
@Composable
private fun WbToast(text: String, modifier: Modifier) {
    var last by remember { mutableStateOf("") }
    if (text.isNotEmpty()) last = text
    AnimatedVisibility(
        text.isNotEmpty(), modifier,
        enter = fadeIn(tween(240)) + slideInVertically(tween(320, easing = K.decel)) { it / 3 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(220, easing = K.accel)) { it / 3 },
    ) {
        Txt(last, K.ink, 14.sp, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.card2).padding(horizontal = 16.dp, vertical = 12.dp), lineHeight = 20.sp)
    }
}

/** 一本书：书头（折叠箭头、书名、已停用、简介、启用数、五个图标）+ 收起展开的条目卡。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookSection(
    b: WorldBook, handle: Modifier, sel: MutableMap<String, Set<String>>, sheet: KSheetState,
    onToast: (String) -> Unit, onExport: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val collapsed = WorldBooks.isCollapsed(b.id)
    val picked = sel[b.id]
    val selecting = picked != null
    val turn by animateFloatAsState(if (collapsed) 0f else 90f, tween(240, easing = outCubic), label = "chev")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            Row(
                Modifier.weight(1f).then(handle).clip(RoundedCornerShape(10.dp))
                    .background(if (pressed) K.ink.copy(alpha = .04f) else Color.Transparent)
                    .combinedClickable(src, null, onClickLabel = if (collapsed) "展开" else "收起") {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); WorldBooks.toggleCollapsed(b.id)
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SvgIcon(WbI.chev, K.ink.copy(alpha = .62f), 16.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { rotationZ = turn })
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(bookName(b), K.ink, 16.sp, Modifier.weight(1f, fill = false), weight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                        if (!b.enabled) {
                            Spacer(Modifier.width(8.dp))
                            Txt("已停用", K.danger, 11.sp, Modifier.clip(RoundedCornerShape(50)).background(K.danger.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
                        }
                    }
                    if (b.description.isNotBlank()) Txt(b.description.trim(), K.ink.copy(alpha = .65f), 12.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
                }
            }
            Spacer(Modifier.width(6.dp))
            Txt("${b.enabledCount}/${b.entries.size}", K.ink.copy(alpha = .6f), 12.sp)
            Spacer(Modifier.width(4.dp))
            if (b.entries.isNotEmpty() && !selecting) HeadIcon(WbI.checks, "多选条目") { sel[b.id] = emptySet(); WorldBooks.expand(b.id) }
            if (!b.novel) HeadIcon(WbI.plus, "添加条目") { sheet.open { EntrySheet(b.id, null, sheet) } }
            HeadIcon(WbI.share, "分享/导出", onClick = onExport)
            HeadIcon(WbI.cfg, "配置") { sheet.open { BookSheet(b, sheet) } }
            HeadIcon(WbI.trash, "删除", color = K.danger) { confirmDeleteBook(b) { sel.remove(b.id) } }
        }
        AnimatedVisibility(
            !collapsed,
            enter = expandVertically(tween(240, easing = inOutCubic), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(240, easing = inOutCubic), shrinkTowards = Alignment.Top),
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.card).padding(vertical = 4.dp)) {
                if (picked != null) SelectionBar(b, picked, sel, onToast)
                if (b.novel) NovelSections(b, picked, sel, sheet, onToast)
                else if (b.entries.isEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { SvgIcon(WbI.tree, K.ink.copy(alpha = .9f), 20.dp, stroke = 1.8f) }
                    Spacer(Modifier.width(8.dp))
                    Txt("暂无条目", K.ink.copy(alpha = .9f), 15.sp)
                } else ReorderableColumn(
                    list = b.entries,
                    onSettle = { from, to -> WorldBooks.moveEntry(b.id, from, to) },
                    onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
                ) { i, e, dragging ->
                    key(e.id) {
                        ReorderableItem {
                            val grip = Modifier.draggableHandle(
                                enabled = b.entries.size > 1 && !selecting,
                                onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            )
                            EntryRow(b, e, grip, dragging, last = i == b.entries.lastIndex, picked = picked, sel = sel, sheet = sheet, onToast = onToast)
                        }
                    }
                }
            }
        }
    }
}

/** 小说世界书：按人物、关系、世界、剧情分段，每段一行小标题和「＋」（新建时预填这一类的模板）。没分类的条目放在最后「其他」。 */
@Composable
private fun NovelSections(b: WorldBook, picked: Set<String>?, sel: MutableMap<String, Set<String>>, sheet: KSheetState, onToast: (String) -> Unit) {
    val kinds = WbKinds.all + if (b.entries.any { it.kind.isEmpty() }) listOf("") else emptyList()
    kinds.forEach { k ->
        val list = b.entries.filter { it.kind == k }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${k.ifEmpty { "其他" }} ${list.size}", K.sub, 12.5.sp, Modifier.weight(1f))
            if (k.isNotEmpty() && picked == null) HeadIcon(WbI.plus, "新建$k", size = 16) { sheet.open { EntrySheet(b.id, null, sheet, k) } }
        }
        list.forEachIndexed { i, e ->
            key(e.id) { EntryRow(b, e, Modifier, false, last = i == list.lastIndex, picked = picked, sel = sel, sheet = sheet, onToast = onToast) }
        }
    }
}

/** 一条条目：把手（按注入位置上色）/ 勾选框、名字 + 位置徽标、已停用 / 常驻、开关。点编辑，长按弹操作。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    b: WorldBook, e: WbEntry, grip: Modifier, dragging: Boolean, last: Boolean,
    picked: Set<String>?, sel: MutableMap<String, Set<String>>, sheet: KSheetState, onToast: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val a = if (e.enabled) 1f else .55f
    val pc = posColor(e.position)
    fun tick() { val s = sel[b.id] ?: return; sel[b.id] = if (e.id in s) s - e.id else s + e.id }
    val on = e.id in WorldBooks.lit
    val glow by androidx.compose.animation.animateColorAsState(if (on) K.acc.copy(alpha = .16f) else Color.Transparent, tween(if (on) 200 else 1200, easing = K.std), label = "wbLit")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (dragging) K.card2 else glow)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .combinedClickable(
                    onClickLabel = entryName(e),
                    onLongClick = if (picked != null) null else ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); sheet.open { EntryActions(b, e, sheet, sel, onToast) } }),
                ) { if (picked != null) tick() else sheet.open { EntrySheet(b.id, e, sheet) } }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 28.dp, height = 44.dp).then(if (picked == null) grip else Modifier), contentAlignment = Alignment.Center) {
                if (picked != null) KCheck(e.id in picked) else SvgIcon(WbI.grip, pc, 20.dp, stroke = 2.6f)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Txt(entryName(e), K.ink.copy(alpha = if (e.enabled) .9f else .5f), 15.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                val label = e.position.label + if (e.position == WbPosition.DEPTH) " · ${e.injectDepth}" else ""
                Txt(label, pc.copy(alpha = a), 11.sp, Modifier.clip(RoundedCornerShape(6.dp)).background(pc.copy(alpha = .12f * a)).padding(horizontal = 7.dp, vertical = 3.dp), maxLines = 1)
            }
            val detail = if (!e.enabled) "已停用" else if (e.constantActive) "常驻" else if (e.matchAll) "同时出现" else null
            if (detail != null) Txt(detail, K.ink.copy(alpha = .6f * a), 13.sp, Modifier.padding(start = 8.dp, end = 6.dp).widthIn(max = 80.dp), maxLines = 1)
            if (picked == null) KSwitch(e.enabled, "启用") { on -> WorldBooks.get(b.id)?.let { cur -> WorldBooks.put(cur.copy(entries = cur.entries.map { if (it.id == e.id) it.copy(enabled = on) else it })) } }
        }
        if (!last && !dragging) Box(Modifier.padding(start = 46.dp, end = 12.dp).fillMaxWidth().height(0.6.dp).background(K.line))
    }
}

/** 多选栏：全选框、已选择 N 项、复制到、移动到、完成。 */
@Composable
private fun SelectionBar(b: WorldBook, picked: Set<String>, sel: MutableMap<String, Set<String>>, onToast: (String) -> Unit) {
    val all = b.entries.isNotEmpty() && picked.size == b.entries.size
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pressable({ sel[b.id] = if (all) emptySet() else b.entries.map { it.id }.toSet() }, Modifier.size(44.dp), label = if (all) "取消全选" else "全选") { KCheck(all) }
        Txt("已选择 ${picked.size} 项", K.sub, 13.sp, Modifier.weight(1f), maxLines = 1)
        HeadIcon(WbI.copy, "复制到…", enabled = picked.isNotEmpty()) { transfer(b, picked, copy = true, sel, onToast) }
        HeadIcon(WbI.move, "移动到…", enabled = picked.isNotEmpty()) { transfer(b, picked, copy = false, sel, onToast) }
        Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 16.dp).background(K.line))
        HeadIcon(WbI.x, "完成") { sel.remove(b.id) }
    }
}

/** 复制 / 移动到另一本（_DestinationPicker）；叠在最上层。 */
private fun transfer(b: WorldBook, ids: Set<String>, copy: Boolean, sel: MutableMap<String, Set<String>>, onToast: (String) -> Unit) {
    AppSheet.open {
        KSheetTitle(if (copy) "复制到…" else "移动到…")
        val others = WorldBooks.books.filter { it.id != b.id }
        if (others.isEmpty()) Txt("请先创建另一本世界书，再移动条目。", K.sub, 14.sp, Modifier.fillMaxWidth().padding(vertical = 18.dp), align = TextAlign.Center)
        others.forEach { o ->
            KOption(bookName(o), false, if (o.enabled) null else "已停用") {
                val n = WorldBooks.transfer(b.id, o.id, ids, copy)
                sel.remove(b.id); AppSheet.close()
                onToast((if (copy) "已复制 " else "已移动 ") + "$n 个条目")
            }
        }
    }
}

/** 长按条目：编辑 / 复制到 / 移动到 / 多选 / 删除，下面单独一块取消（_EntryAction）。 */
@Composable
private fun EntryActions(b: WorldBook, e: WbEntry, sheet: KSheetState, sel: MutableMap<String, Set<String>>, onToast: (String) -> Unit) {
    @Composable
    fun act(icon: Ic, text: String, color: Color = K.ink.copy(alpha = .9f), onClick: () -> Unit) {
        Pressable(onClick, Modifier.fillMaxWidth(), label = text) { pressed ->
            Row(Modifier.fillMaxWidth().background(if (pressed) K.ink.copy(alpha = .05f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp)) { SvgIcon(icon, color, 20.dp, stroke = 1.8f) }
                Spacer(Modifier.width(8.dp))
                Txt(text, color, 15.sp, weight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.card2)) {
        act(WbI.cfg, "编辑条目") { sheet.open { EntrySheet(b.id, e, sheet) } }
        act(WbI.copy, "复制到…") { sheet.close(); transfer(b, setOf(e.id), true, sel, onToast) }
        act(WbI.move, "移动到…") { sheet.close(); transfer(b, setOf(e.id), false, sel, onToast) }
        act(WbI.checks, "多选条目") { sel[b.id] = setOf(e.id); sheet.close() }
        act(WbI.trash, "删除条目", K.danger) {
            sheet.close()
            WorldBooks.get(b.id)?.let { cur -> WorldBooks.put(cur.copy(entries = cur.entries.filter { it.id != e.id })) }
        }
    }
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.card2)) { act(WbI.x, "取消") { sheet.close() } }
}

/** 删除一本书：先问一句。 */
private fun confirmDeleteBook(b: WorldBook, onDeleted: () -> Unit) {
    AppSheet.open {
        KSheetTitle("删除世界书")
        Txt("确定删除「${bookName(b)}」？此操作无法撤销。", K.sub, 14.sp, Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton("取消", Modifier.weight(1f)) { AppSheet.close() }
            KButton("删除", Modifier.weight(1f), color = K.danger) { WorldBooks.remove(b.id); AssistantExt.save(); AppSheet.close(); onDeleted() }
        }
    }
}

/** 添加世界书 / 配置：名称、简介、启用（_WorldBookEditSheet）。 */
@Composable
private fun BookSheet(book: WorldBook?, sheet: KSheetState) {
    var name by remember { mutableStateOf(book?.name.orEmpty()) }
    var desc by remember { mutableStateOf(book?.description.orEmpty()) }
    var on by remember { mutableStateOf(book?.enabled ?: true) }
    var novel by remember { mutableStateOf(book?.novel ?: false) }
    KSheetTitle(if (book == null) "添加世界书" else "配置")
    Group {
        KLabel("名称"); KField(name, { name = it })
        KLabel("简介"); KField(desc, { desc = it }, lines = 2)
        SwitchLine("启用", on) { on = it }
        SwitchLine("按人物、关系、世界、剧情分组", novel) { novel = it }
    }
    Buttons(sheet) {
        val b = (book ?: WorldBook(WbEntry.newId())).copy(name = name.trim(), description = desc.trim(), enabled = on, novel = novel)
        WorldBooks.put(b)
        sheet.close()
    }
}

/** 弹层里的一组：浅一层的圆角块。 */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(K.card2).padding(horizontal = 14.dp, vertical = 6.dp), content = content)
}

@Composable
private fun SwitchLine(text: String, on: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(text, K.ink, 15.sp)
            if (hint != null) Txt(hint, K.sub, 12.sp, Modifier.padding(top = 2.dp))
        }
        KSwitch(on, text, onChange)
    }
}

/** 一行数字：左边名字，右边窄输入框；hint 是下面的小字说明。 */
@Composable
private fun NumLine(text: String, value: Int, signed: Boolean = false, hint: String? = null, onChange: (Int) -> Unit) {
    var raw by remember { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, K.ink, 15.sp, Modifier.weight(1f))
        KField(raw, { v ->
            raw = v.filter { it.isDigit() || (signed && it == '-') }.take(6)
            onChange(raw.toIntOrNull() ?: 0)
        }, Modifier.width(80.dp), number = !signed)
    }
    if (hint != null) Txt(hint, K.sub, 12.sp, Modifier.padding(bottom = 8.dp), lineHeight = 16.sp)
}

/** 一行可点的值：左边名字，右边当前值和箭头。 */
@Composable
private fun ValueLine(text: String, value: String, onClick: () -> Unit) {
    Pressable(onClick, Modifier.fillMaxWidth(), label = text) { pressed ->
        Row(Modifier.fillMaxWidth().background(if (pressed) K.ink.copy(alpha = .05f) else Color.Transparent).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(text, K.ink, 15.sp, Modifier.weight(1f))
            Txt(value, K.sub, 15.sp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(WbI.chev, K.sub, 16.dp, stroke = 1.8f)
        }
    }
}

/** 弹层底部：取消 / 保存。 */
@Composable
private fun Buttons(sheet: KSheetState, canSave: Boolean = true, onSave: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KButton("取消", Modifier.weight(1f)) { sheet.close() }
        Box(Modifier.weight(1f).graphicsLayer { alpha = if (canSave) 1f else .4f }) {
            KButton("保存", Modifier.fillMaxWidth(), primary = true) { if (canSave) onSave() }
        }
    }
}

/** 选一个值（注入位置、注入角色）：叠在编辑弹层上面，当前的打勾。 */
private fun <T> pick(title: String, options: List<Pair<T, String>>, cur: T, onPick: (T) -> Unit) {
    AppSheet.open {
        KSheetTitle(title)
        options.forEach { (v, label) -> KOption(label, v == cur) { onPick(v); AppSheet.close() } }
    }
}

/** 添加 / 编辑条目（_WorldBookEntryEditSheet）：分成几组，常驻或有关键词才能保存。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntrySheet(bookId: String, e0: WbEntry?, sheet: KSheetState, kind: String = "") {
    // 小说世界书里新建：内容预填这一类的模板；关系条目默认两个名字同时出现才触发
    var e by remember { mutableStateOf(e0 ?: WbEntry(WbEntry.newId(), content = WbKinds.template[kind].orEmpty(), kind = kind, matchAll = kind == "关系")) }
    var showEx by remember { mutableStateOf(false) }
    var kw by remember { mutableStateOf("") }
    fun addKw() {
        val parts = kw.split(Regex("[,，\\n]")).map { it.trim() }.filter { it.isNotEmpty() && it !in e.keywords }
        if (parts.isNotEmpty()) e = e.copy(keywords = e.keywords + parts)
        kw = ""
    }
    KSheetTitle(if (e0 != null) "编辑条目" else if (kind.isNotEmpty()) "新建$kind" else "添加条目")
    Group {
        KLabel("条目名称"); KField(e.name, { e = e.copy(name = it) }, hint = if (e.kind.isNotEmpty()) WbKinds.nameHint(e.kind) else "")
        SwitchLine("启用条目", e.enabled) { e = e.copy(enabled = it) }
        // 分类（Kelivo 没有这一项，样式照它的「注入位置」）：选了就归到小说世界书那一段；内容还空着时预填模板
        ValueLine("分类", e.kind.ifEmpty { "无" }) {
            pick("分类", listOf("" to "无") + WbKinds.all.map { it to it }, e.kind) { k ->
                e = e.copy(
                    kind = k,
                    content = if (e.content.isBlank()) WbKinds.template[k].orEmpty() else e.content,
                    matchAll = if (k == "关系") true else if (e.kind == "关系") false else e.matchAll,
                )
            }
        }
    }
    Group {
        // 看示例一直在：没分类时看人物的示例
        val ex = WbKinds.example[e.kind.ifEmpty { "人物" }]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { KLabel("内容") }
            if (ex != null) {
                val turn by animateFloatAsState(if (showEx) 270f else 90f, tween(320, easing = K.decel), label = "exChev")
                Pressable({ showEx = !showEx }, Modifier.padding(top = 6.dp), label = if (showEx) "收起示例" else "看示例") { pressed ->
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(if (pressed) K.acc.copy(alpha = .12f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt("看示例", K.acc, 13.sp)
                        Spacer(Modifier.width(2.dp))
                        SvgIcon(WbI.chev, K.acc, 14.dp, stroke = 1.8f, modifier = Modifier.graphicsLayer { rotationZ = turn })
                    }
                }
            }
        }
        if (ex != null) AnimatedVisibility(
            showEx,
            enter = expandVertically(tween(380, easing = K.decel), expandFrom = Alignment.Top) + fadeIn(tween(320, delayMillis = 60)),
            exit = shrinkVertically(tween(280, easing = K.accel), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
        ) {
            Txt(
                ex, K.sub, 13.sp,
                Modifier.padding(bottom = 10.dp).fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(K.well)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        KField(e.content, { e = e.copy(content = it) }, lines = 8); Spacer(Modifier.height(8.dp))
    }
    Group {
        SwitchLine("常驻激活", e.constantActive, "无需匹配也会注入") { e = e.copy(constantActive = it) }
        KLabel("关键词")
        if (e.keywords.isNotEmpty()) FlowRow(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            e.keywords.forEachIndexed { i, k -> KChip("$k  ×") { e = e.copy(keywords = e.keywords.filterIndexed { j, _ -> j != i }) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            KField(kw, { kw = it }, Modifier.weight(1f), hint = "输入关键词")
            Spacer(Modifier.width(10.dp))
            Pressable({ addKw() }, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(K.well), enabled = kw.isNotBlank(), label = "添加关键词") { _ ->
                SvgIcon(WbI.plus, K.ink, 18.dp, stroke = 1.8f)
            }
        }
        Txt("输入关键词后点 + 添加。", K.sub, 12.5.sp, Modifier.padding(top = 8.dp, bottom = 4.dp))
        SwitchLine("所有关键词同时出现才触发", e.matchAll) { e = e.copy(matchAll = it) }
        SwitchLine("使用正则", e.useRegex) { e = e.copy(useRegex = it) }
        SwitchLine("区分大小写", e.caseSensitive) { e = e.copy(caseSensitive = it) }
        NumLine("扫描深度", e.scanDepth) { e = e.copy(scanDepth = it) }
    }
    Group {
        NumLine("粘滞（消息数）", e.sticky, hint = "触发后在后续 N 条消息中保持激活，重复命中不延长。0 表示关闭。") { e = e.copy(sticky = it.coerceIn(0, 10000)) }
        NumLine("冷却（消息数）", e.cooldown, hint = "触发后（或粘滞结束后）N 条消息内不再触发。0 表示关闭。") { e = e.copy(cooldown = it.coerceIn(0, 10000)) }
        NumLine("延迟（消息数）", e.delay, hint = "对话至少有 N 条消息时才允许触发。按单条消息计数，不是对话轮数。0 表示关闭。") { e = e.copy(delay = it.coerceIn(0, 10000)) }
    }
    Group {
        ValueLine("注入位置", e.position.label) { pick("注入位置", WbPosition.entries.map { it to it.label }, e.position) { e = e.copy(position = it) } }
        AnimatedVisibility(e.position == WbPosition.DEPTH, enter = expandVertically(tween(240, easing = K.decel)) + fadeIn(tween(200)), exit = shrinkVertically(tween(180, easing = K.accel)) + fadeOut(tween(140))) {
            NumLine("注入深度", e.injectDepth) { e = e.copy(injectDepth = it.coerceIn(0, 200)) }
        }
        ValueLine("注入角色", if (e.role == "assistant") "助手" else "用户") { pick("注入角色", listOf("user" to "用户", "assistant" to "助手"), e.role) { e = e.copy(role = it) } }
        NumLine("优先级", e.priority, signed = true) { e = e.copy(priority = it) }
    }
    Buttons(sheet, canSave = e.constantActive || e.keywords.isNotEmpty() || kw.isNotBlank()) {
        if (kw.isNotBlank()) addKw()
        val done = e.copy(name = e.name.trim(), scanDepth = e.scanDepth.coerceIn(1, 200))
        WorldBooks.get(bookId)?.let { b ->
            WorldBooks.put(b.copy(entries = if (b.entries.any { it.id == done.id }) b.entries.map { if (it.id == done.id) done else it } else b.entries + done))
            if (e0 == null) WorldBooks.expand(bookId)
        }
        sheet.close()
    }
}

// ───────── 聊天里的弹层：选世界书、快捷短语 ─────────

object ChatSheets {
    val sheet = KSheetState()

    /** 聊天里点「世界书」（Kelivo world_book_sheet）：勾这位助理用哪几本；助理开着「独立对话指令注入」时可以只对这个对话勾。 */
    fun worldBooks(convId: String?) {
        sheet.open {
            val p = Memory.active
            val x = AssistantExt.of(p)
            var convOnly by remember { mutableStateOf(convId != null && WorldBooks.convPick(convId) != null) }
            val picked = remember { mutableStateListOf<String>().apply { addAll(convId?.let { WorldBooks.convPick(it) }?.takeIf { convOnly } ?: x.worldBooks) } }
            fun commit() {
                if (convOnly && convId != null) WorldBooks.setConvPick(convId, picked.toList())
                else { if (convId != null) WorldBooks.setConvPick(convId, null); x.worldBooks.clear(); x.worldBooks.addAll(picked); AssistantExt.save() }
            }
            KSheetTitle("世界书")
            if (x.perConvInject && convId != null) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("仅当前对话", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(convOnly) { convOnly = it; commit() }
            }
            if (WorldBooks.books.isEmpty()) Txt("暂无世界书", K.sub, 13.5.sp, Modifier.fillMaxWidth().padding(20.dp), align = TextAlign.Center)
            WorldBooks.books.forEach { b ->
                Pressable({ if (b.id in picked) picked.remove(b.id) else picked.add(b.id); commit() }, Modifier.fillMaxWidth(), label = b.name) { pr ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (pr) K.card2 else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        KCheck(b.id in picked)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Txt(b.name.ifBlank { "未命名世界书" }, K.ink, 15.sp); if (!b.enabled) { Spacer(Modifier.width(6.dp)); KTag("已停用") } }
                            Txt("已启用 ${b.enabledCount}/${b.entries.size}", K.sub, 12.sp)
                        }
                    }
                }
            }
        }
    }

    /** 快捷短语（Kelivo 输入框里的短语）：点一条放进输入框。 */
    fun phrases(onPick: (String) -> Unit) {
        sheet.open {
            val x = AssistantExt.of(Memory.active)
            KSheetTitle("快捷短语")
            x.phrases.forEach { ph -> KOption(ph.title, false, ph.content.take(80)) { onPick(ph.content); sheet.close() } }
        }
    }
}

@Composable
fun ChatSheetsLayer() { Box(Modifier.fillMaxSize()) { KSheetHost(ChatSheets.sheet) } }

/** 助理设置 › 聊天背景：设了就铺在聊天页后面（盖住主题的背景画面）。 */
@Composable
fun AssistantBackdrop() {
    val uri = AssistantExt.of(Memory.active).background ?: return
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val u = Uri.parse(uri)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }?.asImageBitmap()
            }.getOrNull()
        }
    }
    img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}
