package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 聊天里的世界书卡（和文件卡同一个样子）：书名 + 「世界书 · 新建 10 条」。点开跳到世界书页，展开这本，刚写的条目亮一下。 */
@Composable
internal fun WbCard(ref: String) {
    val id = WbTools.bookId(ref)
    val gone = WorldBooks.get(id) == null
    Pressable({
        if (gone) return@Pressable
        WorldBooks.lit = WbTools.lit(ref)
        WorldBooks.focus = id
        ChatTools.go?.invoke("worldbook")
    }, Modifier.widthIn(max = 300.dp).fillMaxWidth().padding(vertical = 4.dp), label = "打开世界书 ${WbTools.name(ref)}") { pressed ->
        val k by animateFloatAsState(if (pressed) .97f else 1f, tween(if (pressed) 70 else 180, easing = Curves.standard), label = "wbK")
        Row(
            Modifier.fillMaxWidth().graphicsLayer { scaleX = k; scaleY = k }
                .background(veil(if (pressed) .1f else .07f), RoundedCornerShape(16.dp))
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp, 46.dp).clip(RoundedCornerShape(8.dp)).background(if (gone) veil(.12f) else Color(0xFF4E5A73)), contentAlignment = Alignment.Center) {
                SvgIcon(KI.worldBook, Color.White.copy(alpha = .9f), 20.dp, stroke = 1.6f)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(WorldBooks.get(id)?.name ?: WbTools.name(ref), Page.ink, 14.sp, maxLines = 1)
                Txt(if (gone) "世界书 · 已删除" else "世界书 · " + WbTools.summary(ref), Page.muted, 12.sp, maxLines = 1)
            }
        }
    }
}

/** 她要改已有的条目：思考那一行下面长出一张小卡，「改 / 先不改」（照 SkillAskHost）。 */
@Composable
internal fun WbAskHost(compact: Boolean) {
    val cur = WbTools.asking
    var last by remember { mutableStateOf<WbAsk?>(null) }
    if (cur != null) last = cur
    AnimatedVisibility(
        cur != null,
        enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .82f, stiffness = 420f), initialScale = .94f, transformOrigin = TransformOrigin(0f, 0f)) +
            expandVertically(spring(dampingRatio = .9f, stiffness = 420f), expandFrom = Alignment.Top),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220, easing = Curves.accel), shrinkTowards = Alignment.Top),
    ) { last?.let { WbAskCard(it, compact) } }
}

@Composable
private fun WbAskCard(ask: WbAsk, compact: Boolean) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp).widthIn(max = 520.dp).fillMaxWidth()) {
        Txt(
            "要改《${ask.book}》里已有的 ${ask.names.size} 条：${ask.names.take(6).joinToString("、")}${if (ask.names.size > 6) " 等" else ""}。改吗？",
            Page.ink.copy(alpha = .9f), if (compact) 15.sp else 16.sp, lineHeight = if (compact) 26.sp else 28.sp,
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CardButton(0, BtnTone.Primary, "改", { ask.answer.complete(true) }, Modifier.widthIn(min = 92.dp)) { _, ink ->
                Txt("改", ink, 13.5.sp, Modifier.padding(horizontal = 14.dp))
            }
            CardButton(0, BtnTone.Quiet, "先不改", { ask.answer.complete(false) }, Modifier.widthIn(min = 92.dp)) { _, ink ->
                Txt("先不改", ink, 13.5.sp, Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}
