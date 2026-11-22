package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 方案一「长条展开」：复制了网盘分享链接回到 App，顶上滑下长条（哪家 · 标题 ·「直接下」）。
 * 点「直接下」长条原地往下长成卡片，文件就在里面选；下好停一会儿自己收成一条再退走。点 × 或返回：一次收完。
 */
object PanLink {
    var pill by mutableStateOf<String?>(null)
    var showing by mutableStateOf(false)
    var open by mutableStateOf(false)

    fun show(link: String) { pill = link; open = false; showing = true }
    fun dismiss() { showing = false; open = false }
}

@Composable
fun PanPillLayer() {
    val it = PanStore.item(PanLink.pill)
    if (PanLink.showing && PanLink.open) BackHandler { PanLink.dismiss() }
    // 展开着（在选文件、填提取码、去登录）：不自己收、不跟手拖、点空白不收，只有 × 和返回能收
    val pinned = PanLink.open || it?.waitingLogin == true
    run {
        PillDrop(
            target = PanLink.pill.takeIf { PanLink.showing && it != null },
            busy = it?.save == 1, stay = pinned || (it?.save ?: 0) >= 2 || it?.stage == 2,
            drag = !pinned, outside = !pinned,
            onDismiss = { PanLink.dismiss() },
        ) { link ->
            val item = PanStore.item(link) ?: return@PillDrop
            val seen = remember(item) { item.doneTick }
            LaunchedEffect(item.doneTick) { if (item.doneTick > seen) { kotlinx.coroutines.delay(2800); if (item.save == 2) PanLink.dismiss() } }
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), 22.dp, fill = Skin.cardFill(.86f), border = Color.Transparent)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DiskMark(item.share.disk, 36)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(item.title.ifBlank { "认出一个网盘分享" }, Page.ink, 14.sp, maxLines = 1)
                        val kick = when {
                            item.stage == 0 -> "正在看里面有什么"
                            item.stage == 2 -> item.err?.why ?: "没打开"
                            item.save == 1 -> "在下 ${(item.frac * 100).toInt()}%"
                            item.save == 2 -> "下好了"
                            else -> item.share.disk.label + " · 刚复制的"
                        }
                        Txt(kick, if (item.stage == 2) ClipBad else Page.muted, 11.5.sp, Modifier.padding(top = 1.dp), maxLines = 1)
                    }
                    AnimatedVisibility(!PanLink.open, enter = fadeIn(tween(180)) + scaleIn(tween(180), .9f), exit = fadeOut(tween(140, easing = Curves.accel)) + scaleOut(tween(140, easing = Curves.accel), .9f)) {
                        PressSurface({ PanLink.open = true }, Modifier.height(32.dp), RoundedCornerShape(11.dp), fill = ClipWarm, pressedFill = ClipWarm.copy(alpha = .85f), pressScale = .95f, label = "直接下") {
                            Txt("直接下", Skin.onAccent, 13.sp, Modifier.padding(horizontal = 13.dp))
                        }
                    }
                    Pressable({ PanLink.dismiss() }, Modifier.padding(start = 2.dp), label = "不用") { _ -> SvgIcon(Icons.close, Page.muted, 16.dp, modifier = Modifier.padding(8.dp)) }
                }
                AnimatedVisibility(PanLink.open, enter = expandVertically(tween(380, easing = Curves.decel), Alignment.Top) + fadeIn(tween(240, 80)), exit = shrinkVertically(tween(240, easing = Curves.accel), Alignment.Top) + fadeOut(tween(160))) {
                    PanPane(item, head = false) { PanLink.dismiss() }
                }
            }
        }
    }
}
