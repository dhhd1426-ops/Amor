package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 编辑 / 添加一条记忆（原型 V6rZ3aFf6r4CsHKGDvUEx9）：
 * 标签一排（内置四个 + 全 App 共用的自定义标签；＋ 新起，长按自定义标签变红再点删）→ 标题 → 有正文时正文 → 删除 / 保存。
 * 删除不再先问，靠底部「撤销」兜底。
 */
object MemEdit {
    private val BUILTIN = listOf("about", "pref", "promise", "know")

    /** 刚删掉的那条：助理设置的记忆页底下弹「撤销」。 */
    var undo by mutableStateOf<Triple<Persona, Mem, Int>?>(null)

    /** m = null 是添加。onDeleted 不传时用 [undo] 的撤销条。 */
    fun open(p: Persona, m: Mem?, onDeleted: ((Mem, Int) -> Unit)? = null) {
        val n = System.nanoTime()
        AppSheet.open { key(n) { Form(p, m, onDeleted ?: { d, i -> undo = Triple(p, d, i) }) } }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Form(p: Persona, m: Mem?, onDeleted: (Mem, Int) -> Unit) {
        var kind by remember { mutableStateOf(m?.kind ?: "about") }
        var title by remember { mutableStateOf(m?.text.orEmpty()) }
        var body by remember { mutableStateOf(m?.diary.orEmpty()) }
        var armed by remember { mutableStateOf<String?>(null) }
        val hasBody = m != null && m.diary.isNotBlank()
        val ok = title.isNotBlank() && (m == null || title.trim() != m.text || kind != m.kind || (hasBody && body.trim() != m.diary))
        // 周、月、年那几份是整理出来的，不换标签
        if (m?.span != true) {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BUILTIN.forEach { k -> Chip(MemTree.kindZh(k), on = kind == k || (k == "know" && kind == "talk"), armed = false, onLong = null) { armed = null; kind = k } }
                Memory.tags.forEach { n ->
                    val k = Mem.TAG + n
                    Chip(n, on = kind == k, armed = armed == k, onLong = { armed = k }) {
                        if (armed == k) {
                            armed = null
                            Memory.dropTag(n)
                            if (kind == k) kind = "know"
                        } else { armed = null; kind = k }
                    }
                }
                NewTag { name ->
                    armed = null
                    val hit = BUILTIN.firstOrNull { MemTree.kindZh(it) == name }
                    kind = hit ?: Memory.addTag(name)
                }
            }
        }
        Field(title, { title = it }, if (m == null) "记下一件事" else "", strong = hasBody, minH = if (hasBody) 0 else 96)
        if (hasBody) {
            Spacer(Modifier.height(10.dp))
            Field(body, { body = it }, "", strong = false, minH = 150, soft = true)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m != null) KButton("删除", Modifier.weight(1f), color = K.danger) {
                val i = p.items.indexOfFirst { it.id == m.id }
                AppSheet.close()
                if (i >= 0) { val cur = p.items[i]; Memory.remove(p, cur); onDeleted(cur, i) }
            }
            KButton("保存", Modifier.weight(1f).alpha(if (ok) 1f else .35f), primary = true) {
                if (!ok) return@KButton
                if (m == null) Memory.note(p, kind, title) else Memory.edit(p, m.id, title, body, kind)
                AppSheet.close()
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Chip(text: String, on: Boolean, armed: Boolean, onLong: (() -> Unit)?, onClick: () -> Unit) {
        val haptic = LocalHapticFeedback.current
        val bg by animateColorAsState(if (armed) K.danger.copy(alpha = .16f) else if (on) K.acc.copy(alpha = .2f) else K.card2, tween(160, easing = K.std), label = "memChipBg")
        val fg by animateColorAsState(if (armed) K.danger else if (on) K.acc else K.sub, tween(160, easing = K.std), label = "memChipFg")
        Txt(
            if (armed) "$text  ×" else text, fg, 13.5.sp,
            Modifier.clip(RoundedCornerShape(50)).background(bg)
                .combinedClickable(remember { MutableInteractionSource() }, null, onClick = onClick,
                    onLongClick = onLong?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } })
                .padding(horizontal = 14.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }

    /** ＋：点了原地变成一个小输入框，回车或点别处就起好这个标签。 */
    @Composable
    private fun NewTag(onAdd: (String) -> Unit) {
        var editing by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val fr = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        fun done() {
            if (!editing) return
            val n = name.trim().take(8)
            editing = false; name = ""; focused = false
            if (n.isNotEmpty()) onAdd(n)
        }
        if (!editing) {
            Pressable({ editing = true }, Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(K.card2), label = "新标签") { pr ->
                Txt("+", if (pr) K.ink else K.sub, 19.sp, maxLines = 1)
            }
        } else {
            LaunchedEffect(Unit) { fr.requestFocus() }
            BasicTextField(
                name, { name = it.take(8) },
                Modifier.widthIn(min = 96.dp, max = 160.dp).clip(RoundedCornerShape(50)).background(K.card2)
                    .focusRequester(fr)
                    .onFocusChanged { if (it.isFocused) focused = true else if (focused) done() }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                textStyle = TextStyle(color = K.ink, fontSize = 13.5.sp, fontFamily = Sans),
                cursorBrush = SolidColor(K.acc),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                decorationBox = { inner -> Box { if (name.isEmpty()) Txt("新标签", K.dim, 13.5.sp, maxLines = 1); inner() } },
            )
        }
    }

    @Composable
    private fun Field(v: String, on: (String) -> Unit, hint: String, strong: Boolean, minH: Int, soft: Boolean = false) {
        BasicTextField(
            v, on,
            Modifier.fillMaxWidth().heightIn(min = minH.dp).clip(RoundedCornerShape(16.dp)).background(K.well).padding(horizontal = 16.dp, vertical = 14.dp),
            textStyle = TextStyle(
                color = if (soft) K.ink.copy(alpha = .85f) else K.ink, fontSize = 15.5.sp, lineHeight = if (soft) 26.sp else 24.sp,
                fontFamily = Sans, fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            ),
            cursorBrush = SolidColor(K.acc),
            decorationBox = { inner -> Box { if (v.isEmpty() && hint.isNotEmpty()) Txt(hint, K.dim, 15.5.sp); inner() } },
        )
    }
}

/** 助理设置记忆页底部：删掉后 4 秒内能撤销。 */
@Composable
internal fun MemUndoBar(modifier: Modifier) {
    val u = MemEdit.undo
    var last by remember { mutableStateOf(u) }
    if (u != null) last = u
    LaunchedEffect(u) { if (u != null) { delay(4000); if (MemEdit.undo === u) MemEdit.undo = null } }
    val out = outsideTap(u != null) { MemEdit.undo = null }
    AnimatedVisibility(
        u != null, modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(420, easing = K.decel)) { it },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(280, easing = K.accel)) { it },
    ) {
        Row(
            out.clip(RoundedCornerShape(50)).background(K.pill).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("已删除", K.ink, 14.sp, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Pressable({ last?.let { (p, m, i) -> Memory.restore(p, m, i) }; MemEdit.undo = null }, label = "撤销") { pr ->
                Txt("撤销", K.acc, 14.sp, Modifier.clip(RoundedCornerShape(50)).background(K.acc.copy(alpha = if (pr) .26f else .16f)).padding(horizontal = 14.dp, vertical = 7.dp), maxLines = 1)
            }
        }
    }
}
