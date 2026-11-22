package com.fuguang.lueying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃的实心胶囊（第 558 版，侧栏「新对话」，原型 MpF6dSpt1bchrjBvjnBEUC「液态玻璃 + 按下放大」）。
 * 颜色还是原来的反色（深色主题奶油白、浅色主题深色），上面叠四层：
 * 表面上半渐隐的高光、下沿柔光、上沿 1.5dp 亮边（直边均匀，圆头被裁掉自然衰减，照 FlClash 量的 iOS）、下沿压深一线；外面一层阴影浮起来。
 * 按下：弹性放大 1.06，手指下亮一团光跟着走；松手弹回（阻尼 .65，过冲在动效指南的 10% 以内）。
 */
@Composable
fun LiquidPill(text: String, icon: Ic, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var at by remember { mutableStateOf(Offset.Unspecified) }
    Pressable(onClick, modifier, label = text) { p ->
        val k by animateFloatAsState(
            if (p) 1.06f else 1f,
            if (p) spring(dampingRatio = .75f, stiffness = 700f) else spring(dampingRatio = .65f, stiffness = 380f),
            label = "pillK",
        )
        val glow by animateFloatAsState(if (p) 1f else 0f, tween(if (p) 120 else 250), label = "pillGlow")
        val dark = !Skin.light
        val fill = Page.ink.copy(alpha = if (dark) .96f else .86f)
        val spec = Color.White.copy(alpha = if (dark) .7f else .16f)
        val bloom = Color.White.copy(alpha = if (dark) .35f else .08f)
        val rim = Color.White.copy(alpha = if (dark) .95f else .28f)
        val deep = if (dark) Color(0x1F785A46) else Color.Black.copy(alpha = .3f)
        val touch = Color.White.copy(alpha = if (dark) .75f else .22f)
        val shape = RoundedCornerShape(50)
        Row(
            Modifier
                .graphicsLayer { scaleX = k; scaleY = k }
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = if (dark) .6f else .35f))
                .clip(shape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        at = down.position
                        while (true) {
                            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            at = c.position
                            if (!c.pressed) break
                        }
                    }
                }
                .drawBehind {
                    drawRect(fill)
                    drawRect(Brush.verticalGradient(0f to spec, .48f to Color.Transparent))
                    drawRect(Brush.radialGradient(listOf(bloom, Color.Transparent), Offset(size.width / 2, size.height * 1.2f), size.width * .6f))
                    val band = 1.5.dp.toPx()
                    drawRect(Brush.verticalGradient(0f to rim, 1f to Color.Transparent, startY = 0f, endY = band), size = Size(size.width, band))
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 1f to deep, startY = size.height - band, endY = size.height),
                        topLeft = Offset(0f, size.height - band), size = Size(size.width, band),
                    )
                    if (glow > 0f && at.isSpecified) {
                        val r = 60.dp.toPx()
                        drawCircle(Brush.radialGradient(listOf(touch.copy(alpha = touch.alpha * glow), Color.Transparent), at, r), r, at)
                    }
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SvgIcon(icon, Skin.onInk, 15.dp, stroke = 2f)
            Spacer(Modifier.width(8.dp))
            Txt(text, Skin.onInk, 14.5.sp, weight = FontWeight.Medium)
        }
    }
}
