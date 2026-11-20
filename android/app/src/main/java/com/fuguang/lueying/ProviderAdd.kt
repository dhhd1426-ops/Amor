package com.fuguang.lueying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 全 App 共用的一个底部弹层：聊天里、设置里都能直接打开（画在 SheetLayerHost 那一层）。 */
val AppSheet = KSheetState()

/**
 * 添加供应商（照 Kelivo features/provider/widgets/add_provider_sheet.dart）：接口类型 OpenAI 兼容 / Claude，
 * 是否启用、名称、API Key、API Base Url，OpenAI 兼容多一个 API 路径。加好后 [onAdded] 拿到新 id，去它的配置页测试连接、获取模型。
 */
fun openAddProvider(store: AssistantStore, onAdded: (String) -> Unit) {
    AppSheet.open { AddProviderForm(store, onAdded) }
}

@Composable
private fun AddProviderForm(store: AssistantStore, onAdded: (String) -> Unit) {
    var kind by remember { mutableStateOf("openai") }
    var on by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val defBase = if (kind == "anthropic") "https://api.anthropic.com/v1" else "https://api.openai.com/v1"
    KSheetTitle("添加供应商")
    KSeg(listOf("openai" to "OpenAI 兼容", "anthropic" to "Claude"), { kind == it }) { kind = it; err = "" }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("是否启用", K.ink, 15.sp, Modifier.weight(1f)); KSwitch(on, "是否启用") { on = it }
    }
    KLabel("名称"); KField(name, { name = it }, hint = if (kind == "anthropic") "Claude" else "OpenAI")
    KLabel("API Key"); KField(key, { key = it.trim() }, hint = "sk-…", mono = true)
    KLabel("API Base Url"); KField(base, { base = it.trim(); err = "" }, hint = defBase, mono = true)
    AnimatedVisibility(
        kind == "openai",
        enter = expandVertically(tween(240, easing = K.decel)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(180, easing = K.accel)) + fadeOut(tween(140)),
    ) {
        Column { KLabel("API 路径"); KField(path, { path = it.trim() }, hint = "/chat/completions", mono = true) }
    }
    if (err.isNotEmpty()) Txt(err, K.danger, 12.5.sp, Modifier.padding(top = 8.dp, start = 2.dp))
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KButton("取消", Modifier.weight(1f)) { AppSheet.close() }
        KButton("添加", Modifier.weight(1f), primary = true) {
            val url = base.ifBlank { defBase }.trimEnd('/')
            if (!Regex("^https?://[^\\s/]+\\.[^\\s]+$").matches(url)) { err = "Base Url 要以 http:// 或 https:// 开头"; return@KButton }
            val id = store.addProvider(kind, name, key, url, if (kind == "openai") path else "", on)
            AppSheet.close()
            onAdded(id)
        }
    }
}

/** 删除自己加的供应商：先问一句。 */
fun confirmRemoveProvider(store: AssistantStore, id: String, onRemoved: () -> Unit) {
    AppSheet.open {
        KSheetTitle("删除供应商")
        Txt("确定要删除「${store.config(id).name}」吗？此操作无法撤销。", K.sub, 14.sp, Modifier.padding(bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KButton("取消", Modifier.weight(1f)) { AppSheet.close() }
            KButton("删除", Modifier.weight(1f), color = K.danger) { store.removeProvider(id); AppSheet.close(); onRemoved() }
        }
    }
}
