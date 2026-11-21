package com.fuguang.lueying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话大纲（原型 P5sAbWS3bPbmh56j6D4i4U，照 Claude App）：右上角 ⋮ →「对话大纲」，从那里长出一张卡：
 * 搜索框 + 每条消息（头像、谁、前两行、时间）。搜到的词淡淡标出来；点一条，卡收起，聊天滚到那条亮一下（借前情的 FoldUi.jump）。
 * 颜色全用主题里的（Page.ink / muted、Skin.well、Skin.accent），卡片底由 CenterCardLayer 按主题给。
 */
object OutlineCard : FloatCard() {
    var conv by mutableStateOf("")
    fun show(id: String, at: Offset) { conv = id; from = at; open = true }
}

private val searchIc = Ic("M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13ZM15.5 15.5 20 20")
private val dayFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

@Composable
fun OutlineCardLayer() {
    CenterCardLayer(OutlineCard, "outline", { 0 }, onBack = { false }, onClosed = { OutlineCard.conv = "" }) { _, rise ->
        val c = Chats.current?.history?.get(OutlineCard.conv)
        var q by remember(OutlineCard.conv) { mutableStateOf("") }
        val her = c?.who?.let { Memory.get(it) }
        val rows = remember(c?.msgs, q) {
            val k = q.trim()
            c?.msgs.orEmpty().withIndex().filter { (_, m) -> m.role == "user" || m.role == "assistant" }
                .filter { (_, m) -> k.isEmpty() || m.text.contains(k, ignoreCase = true) }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            Txt("对话大纲", Page.ink, 17.sp, Modifier.padding(end = 40.dp, bottom = 14.dp).mineRise(0, rise), family = Serif)
            Row(
                Modifier.fillMaxWidth().mineRise(1, rise).background(Skin.well(.22f), RoundedCornerShape(14.dp)).height(46.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SvgIcon(searchIc, Page.muted, 18.dp, stroke = 1.6f)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    q, { q = it }, Modifier.weight(1f),
                    textStyle = TextStyle(color = Page.ink, fontSize = 15.sp, fontFamily = Sans),
                    cursorBrush = SolidColor(Skin.accent),
                    singleLine = true,
                    decorationBox = { inner -> Box { if (q.isEmpty()) Txt("消息里的词", Page.muted, 15.sp, maxLines = 1); inner() } },
                )
            }
            Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) Txt(if (q.isBlank()) "这段对话还没有消息" else "没有找到", Page.muted, 14.sp, Modifier.fillMaxWidth().padding(top = 36.dp).mineRise(2, rise))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth().mineRise(2, rise), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(rows, key = { it.index }) { (i, m) -> Line(i, m, her, q.trim()) }
            }
        }
    }
}

@Composable
private fun Line(i: Int, m: ChatMsg, her: Persona?, k: String) {
    val me = m.role == "user"
    Pressable({
        OutlineCard.open = false
        FoldUi.jump = (i + 1)..(i + 1)
    }, Modifier.fillMaxWidth(), label = "跳到这条") { pressed ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (pressed) veil(.07f) else Color.Transparent).padding(horizontal = 8.dp, vertical = 11.dp),
        ) {
            if (me) AvatarFace(Avatars.ME, "你", Brush.linearGradient(listOf(veil(.16f), veil(.16f))), 30.dp, ink = Page.ink)
            else if (her != null) PersonaDot(her, 30.dp)
            else Box(Modifier.size(30.dp).background(veil(.16f), RoundedCornerShape(15.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(if (me) "你" else her?.name?.trim().orEmpty().ifEmpty { "她" }, Page.ink, 13.5.sp, maxLines = 1)
                BasicText(
                    preview(m, k),
                    style = TextStyle(color = Page.ink.copy(alpha = .9f), fontSize = 14.5.sp, lineHeight = 22.sp, fontFamily = Sans),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Txt(dayFmt.format(Date(m.at)), Page.muted, 11.5.sp, maxLines = 1)
            }
        }
    }
}

/** 前两行：去掉 Markdown 记号；有搜索词时从词前面一点开始，词标上主题色的淡底。 */
private fun preview(m: ChatMsg, k: String): AnnotatedString {
    var t = m.text.replace(Regex("[*#`>]"), "").replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) {
        val n = m.files.size + m.imgs.size
        t = if (n > 0) "（$n 个附件）" else "…"
    }
    if (k.isEmpty()) return AnnotatedString(t.take(200))
    val p = t.indexOf(k, ignoreCase = true)
    if (p > 14) t = "…" + t.substring(p - 14)
    t = t.take(200)
    return buildAnnotatedString {
        append(t)
        var s = t.indexOf(k, ignoreCase = true)
        while (s >= 0) {
            addStyle(SpanStyle(background = Skin.accent.copy(alpha = .22f)), s, s + k.length)
            s = t.indexOf(k, s + k.length, ignoreCase = true)
        }
    }
}
