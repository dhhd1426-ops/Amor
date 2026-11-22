package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val gWarm: Color get() = Skin.accent
private val gChevron = Ic("M9.5 6l6 6-6 6")
private val gCheck = Ic("M5 12.5l4.5 4.5L19 7.5")
private val gOut = Ic("M9 7h8v8M17 7L7 17")
private val gGauge = Ic("M5 16a7 7 0 1 1 14 0M12 16l3.5-4.5")
private val gNote = Ic("M6 5h12v14H6zM9 9h6M9 12.5h6M9 16h3")
internal val gptGlyph = Ic("M5.5 6.5h13a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM8 10l2.5 2L8 14M13 14h3")

/** 功能页那一行底下的小字：现在走到哪一步。 */
internal fun gptSub(store: AssistantStore): String = when {
    !Gpt.on -> "关着"
    !Gpt.signedIn -> "还没登录"
    Gpt.using(store).isEmpty() -> "还没选模型"
    else -> Gpt.title(Gpt.using(store))
}

/**
 * 「我的 › 功能 › GPT 控制台」（原型 N2wWeu3rzPakdq6ctBHZQf）：启用 → 用 ChatGPT 继续（拉起浏览器）→ 列出能用的模型 →
 * 点一个先测试，通了所有聊天走它。额度官方不让应用读，只放一个去官方用量页的入口。
 */
@Composable
internal fun GptPage(store: AssistantStore, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Gpt.refreshModels() }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        CardPageHead("GPT 控制台", onBack = onBack)
        Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("启用", Page.ink, 15.sp, Modifier.weight(1f))
            CardSwitch(Gpt.on, "启用 GPT 控制台") { Gpt.toggle(it) }
        }
        AnimatedVisibility(
            Gpt.on,
            enter = expandVertically(tween(420, easing = Curves.decel)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(280, easing = Curves.accel)) + fadeOut(tween(160)),
        ) {
            if (Gpt.signedIn) GptSigned(store) else GptLogin()
        }
    }
}

@Composable
private fun GptLabel(text: String) = Txt(text, Page.muted, 11.sp, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp), spacing = ls(2.75f, 11f))

@Composable
private fun GptLogin() {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        if (Gpt.phase.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                LiquidPill(if (Gpt.loginDiag.isEmpty()) "用 ChatGPT 继续" else "再登录一次", gOut, { Gpt.login() })
            }
        } else {
            Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GptSpin(Page.ink, 15.dp)
                Spacer(Modifier.width(10.dp))
                Txt(if (Gpt.phase == "wait") "等浏览器回来" else "正在核对登录", Page.ink, 14.5.sp, Modifier.weight(1f), maxLines = 1)
                PressSurface({ Gpt.cancelLogin() }, shape = RoundedCornerShape(50), fill = Color.Transparent, pressScale = .94f, label = "取消登录") {
                    Txt("取消", Page.muted, 13.sp, Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                }
            }
        }
        if (Gpt.loginDiag.isNotEmpty()) { Spacer(Modifier.height(12.dp)); DiagBox(Gpt.loginDiag) }
    }
}

@Composable
private fun GptSigned(store: AssistantStore) {
    val uri = LocalUriHandler.current
    val using = Gpt.using(store)
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val shown = Gpt.who.ifBlank { Gpt.mail }.ifBlank { "ChatGPT 账号" }
            Box(Modifier.size(34.dp).background(veil(.1f), CircleShape), contentAlignment = Alignment.Center) {
                Txt(shown.take(1).uppercase(), gWarm, 14.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(shown, Page.ink, 14.5.sp, maxLines = 1)
                if (Gpt.mail.isNotBlank() && Gpt.mail != shown) Txt(Gpt.mail, Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), maxLines = 1)
            }
            PressSurface({ Gpt.signOut() }, shape = RoundedCornerShape(50), fill = Color.Transparent, pressScale = .94f, label = "退出登录") {
                Txt("退出登录", Page.muted, 13.sp, Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }

        GptLabel("可调用的模型")
        Column(Modifier.fillMaxWidth().background(CardBlock, RoundedCornerShape(18.dp)).padding(6.dp)) {
            if (Gpt.models.isEmpty()) Txt("这个账号下没有列出模型", Page.muted, 13.sp, Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
            Gpt.models.forEach { m ->
                val testing = Gpt.testing == m.slug
                PressSurface(
                    { if (using != m.slug) Gpt.test(m.slug) },
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), fill = Color.Transparent, pressScale = .98f,
                    enabled = Gpt.testing.isEmpty(), label = "用 ${m.name}",
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(m.name, Page.ink, 14.5.sp, maxLines = 1)
                            if (m.slug != m.name) Txt(m.slug, Page.muted, 11.5.sp, Modifier.padding(top = 2.dp), family = FontFamily.Monospace, maxLines = 1)
                        }
                        if (testing) {
                            GptSpin(Page.muted, 12.dp)
                            Spacer(Modifier.width(7.dp))
                            Txt("测试中", Page.muted, 12.sp, maxLines = 1)
                        } else if (using == m.slug) {
                            Txt("测试成功", gWarm, 12.sp, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            SvgIcon(gCheck, gWarm, 18.dp, stroke = 1.8f)
                        }
                    }
                }
            }
        }
        if (Gpt.testDiag.isNotEmpty()) { Spacer(Modifier.height(10.dp)); DiagBox(Gpt.testDiag) }

        Spacer(Modifier.height(14.dp))
        MineRow(0, 1f, "管理用量", "", { runCatching { uri.openUri(Gpt.USAGE_URL) } }, glyph = { SvgIcon(gGauge, gWarm, 19.dp) }) {
            SvgIcon(gOut, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
        Spacer(Modifier.height(6.dp))
        MineRow(1, 1f, "诊断", "", { val text = Gpt.report(store); AppSheet.open { KSheetTitle("诊断"); DiagBox(text) } }, glyph = { SvgIcon(gNote, gWarm, 19.dp) }) {
            SvgIcon(gChevron, Page.muted.copy(alpha = .6f), 15.dp, stroke = 1.8f)
        }
    }
}

/** 转着的小圈：正在等。 */
@Composable
private fun GptSpin(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "gptSpin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "gptSpinA")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * .13f
        val inset = w / 2
        val box = Size(this.size.width - w, this.size.height - w)
        drawArc(color.copy(alpha = .22f), 0f, 360f, false, topLeft = Offset(inset, inset), size = box, style = Stroke(w))
        drawArc(color, a, 90f, false, topLeft = Offset(inset, inset), size = box, style = Stroke(w, cap = StrokeCap.Round))
    }
}
