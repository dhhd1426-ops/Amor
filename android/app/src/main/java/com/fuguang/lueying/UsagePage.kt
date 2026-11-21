package com.fuguang.lueying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.roundToInt

private val gauge = Ic("M5 17a8 8 0 1 1 14 0m-7-4 3-3", circles = listOf(floatArrayOf(12f, 13f, 1.1f)))
private val refreshIcon = Ic("M20 11a8 8 0 1 0-2 6m2-11v5h-5")
private val arrow = Ic("M9.5 6l6 6-6 6")
private val usageWarning = Color(0xFFE07A6E)

private fun money(currency: String, value: Double): String {
    val formatted = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(value)
    return (if (currency == "CNY") "¥" else "US\$") + formatted
}

private fun count(n: Long) = NumberFormat.getIntegerInstance().format(n)
private fun percent(fraction: Float) = "已用 ${(fraction * 100).roundToInt()}%"
@Composable
private fun UsageMeter(fraction: Float, modifier: Modifier = Modifier) {
    val tint = if (fraction >= .85f) usageWarning else Color(0xFF7FB88A)
    Box(modifier.fillMaxWidth().height(6.dp).background(tint.copy(alpha = .19f), RoundedCornerShape(50))
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tint, RoundedCornerShape(50)))
    }
}

/** 没有上限时，横条只表达已记录的输入和输出构成。 */
@Composable
private fun TokenMix(totals: ModelUsage.Totals, modifier: Modifier = Modifier) {
    val sum = totals.input.toDouble() + totals.output
    val inputShare = if (sum > 0) (totals.input / sum).toFloat() else 0f
    val outputTint = Skin.accent
    val inputTint = Color(0xFF7FB88A)
    val description = if (sum > 0) "本机 Token 构成，输入 ${count(totals.input)}，输出 ${count(totals.output)}" else "暂无已记录的 Token"
    Box(modifier.fillMaxWidth().height(6.dp)
        .background(if (sum > 0) outputTint else Page.muted.copy(alpha = .18f), RoundedCornerShape(50))
        .semantics { contentDescription = description }) {
        if (inputShare > 0f) Box(Modifier.fillMaxWidth(inputShare).fillMaxHeight().background(inputTint, RoundedCornerShape(50)))
    }
}

@Composable
private fun rememberUsage(p: Provider, config: ProviderConfig, model: String, now: Long): ModelUsage.Snapshot {
    val route = remember(p.id, config.base, config.key) { ModelUsage.accountKey(p, config) }
    val key = remember(route, config.path, model) { ModelUsage.modelKey(route, config, model) }
    val revision by ModelUsage.revision.collectAsState()
    return remember(route, key, now, revision) { ModelUsage.snapshot(route, key, now) }
}

@Composable
internal fun MineUsageRow(store: AssistantStore, t: Float, onOpen: () -> Unit) {
    val p = provider(store.activeProvider)
    val config = store.config(p.id)
    val model = store.activeModel
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(5000); value = System.currentTimeMillis() }
    }
    val snapshot = rememberUsage(p, config, model, now)
    LaunchedEffect(p.id, config.base, config.key) {
        if (ModelUsage.canRefresh(config) && (snapshot.account == null || System.currentTimeMillis() - snapshot.account.at > 30_000)) {
            ModelUsage.refresh(p, config)
        }
    }
    val tokenRate = snapshot.entryTokenRate
    val totals = snapshot.totals
    val detail = when {
        !config.configured -> "尚未配置模型"
        tokenRate != null -> "${tokenRate.name} · ${percent(tokenRate.fraction)}"
        totals.input + totals.output > 0 -> "近 7 天 · ${count(totals.input + totals.output)} Token"
        else -> "暂无 Token 记录"
    }
    Box(Modifier.spot("usage")) {
        MineRow(3, t, "用量", "", onOpen, glyph = { SvgIcon(gauge, Skin.accent, 19.dp) }, subSlot = {
            val meterPadding = Modifier.padding(top = 3.dp, bottom = 5.dp)
            if (tokenRate != null) UsageMeter(tokenRate.fraction, meterPadding)
            else TokenMix(totals, meterPadding)
            Txt(detail, Page.muted, 12.sp, maxLines = 1)
        }) {
            if (config.configured) ProviderLogo(p.id, p.letter, 24.dp)
            Spacer(Modifier.width(4.dp))
            SvgIcon(arrow, Page.muted, 15.dp)
        }
    }
}

@Composable
internal fun ColumnScope.UsagePage(store: AssistantStore, compact: Boolean, onBack: () -> Unit) {
    // 默认看正在用的那家；在余额弹层里手动选了别家就看那家，换模型时回到正在用的
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store.activeProvider, store.activeModel) { picked = null }
    val p = provider(picked ?: store.activeProvider)
    val config = store.config(p.id)
    val model = if (picked == null) store.activeModel else config.models.firstOrNull().orEmpty()
    var refresh by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var arrived by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val snapshot = rememberUsage(p, config, model, now)
    LaunchedEffect(p.id, config.base, config.key, refresh) {
        refreshing = ModelUsage.canRefresh(config)
        try {
            if (refreshing) {
                val result = ModelUsage.refresh(p, config)
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            }
        } finally { refreshing = false }
    }
    // 发完消息 2 秒后用官方余额校准（30 秒内最多一次）
    val revision by ModelUsage.revision.collectAsState()
    LaunchedEffect(revision) {
        delay(2000)
        val at = snapshot.account?.at ?: 0L
        if (System.currentTimeMillis() - at > 30_000) refresh++
    }
    // 从微信 / 支付宝付完回来：立刻刷一次，再隔几秒补两次（到账可能稍慢）
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val curP by rememberUpdatedState(p)
    val curC by rememberUpdatedState(config)
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME && System.currentTimeMillis() - DeepSeekPay.launchedAt < 30 * 60_000L) {
                DeepSeekPay.launchedAt = 0L
                scope.launch {
                    val before = DeepSeekPay.before
                    for (wait in listOf(600L, 4000L, 8000L)) {
                        delay(wait)
                        ModelUsage.refresh(curP, curC)
                        val acc = ModelUsage.snapshot(ModelUsage.accountKey(curP, curC), "", System.currentTimeMillis()).account
                        val after = acc?.remaining
                        if (before != null && after != null && after > before + 0.005) {
                            arrived = "到账 ${money(acc.currency, after - before)}"
                            delay(4000); arrived = null
                            break
                        }
                    }
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Title("用量", compact, onBack) {
        if (ModelUsage.canRefresh(config)) Pressable(
            { if (!refreshing) refresh++ }, Modifier.size(40.dp), label = "刷新余额",
        ) { pressed ->
            val dim by animateFloatAsState(if (refreshing) .4f else 1f, tween(180), label = "usageDim")
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = if (pressed) .92f else 1f; scaleY = scaleX; alpha = dim }, contentAlignment = Alignment.Center) {
                SvgIcon(refreshIcon, Skin.accent, 20.dp)
            }
        }
    }
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BalanceCard(store, p, config, model, snapshot.account, arrived) { id -> picked = if (id == store.activeProvider) null else id }
        Spacer(Modifier.height(10.dp))
        StatsCard(store.activeModel, revision)
    }
}

/* ---------- 余额卡 ---------- */

@Composable
private fun BalanceCard(
    store: AssistantStore, p: Provider, config: ProviderConfig, model: String,
    account: ModelUsage.Account?, arrived: String?, onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(veil(.06f), RoundedCornerShape(18.dp))) {
        Pressable({ AppSheet.open { ProviderBalances(store, p.id) { id -> onPick(id); AppSheet.close() } } }, Modifier.fillMaxWidth(), label = "切换供应商") { pressed ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(if (pressed) veil(.04f) else Color.Transparent)
                    .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderLogo(p.id, p.letter, 36.dp, dim = !config.configured)
                Spacer(Modifier.width(11.dp))
                AnimatedContent(config.name.ifBlank { p.name } to model.ifBlank { "未选择模型" }, Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(240, easing = Curves.decel)) + slideInVertically(tween(240, easing = Curves.decel)) { it / 4 })
                        .togetherWith(fadeOut(tween(140, easing = Curves.accel)) + slideOutVertically(tween(140, easing = Curves.accel)) { -it / 4 })
                }, label = "usageWho") { (name, shown) ->
                    Column {
                        Txt(name, Page.muted, 12.sp, maxLines = 1)
                        Txt(shown, Page.ink, 15.sp, maxLines = 1)
                    }
                }
                SvgIcon(chevDown, Page.muted, 18.dp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp)) {
            when {
                !config.configured -> Txt("尚未配置", Page.muted, 13.sp, Modifier.padding(top = 4.dp))
                account == null -> Txt(if (ModelUsage.canRefresh(config)) "正在获取余额…" else "不支持查询余额", Page.muted, 13.sp, Modifier.padding(top = 4.dp))
                else -> {
                    val remaining by animateFloatAsState(account.remaining.toFloat(), tween(460, easing = Curves.standard), label = "usageLeft")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Txt(money(account.currency, remaining.toDouble()), Page.ink, 28.sp, maxLines = 1)
                        account.full?.let { Txt(" / ${money(account.currency, it)}", Page.muted, 14.sp, Modifier.padding(bottom = 4.dp), maxLines = 1) }
                        Spacer(Modifier.weight(1f))
                        account.fraction?.let { Txt(percent(it), Page.muted, 13.sp, Modifier.padding(bottom = 4.dp)) }
                    }
                    account.fraction?.let { f ->
                        val shown by animateFloatAsState(f, tween(460, easing = Curves.standard), label = "usageBar")
                        val low = account.full != null && account.remaining / account.full!! < .15
                        val tint by animateColorAsState(if (low) usageWarning else Skin.accent, tween(320), label = "usageTint")
                        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(veil(.08f))
                            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) }) {
                            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = shown; transformOrigin = TransformOrigin(0f, .5f) }.clip(RoundedCornerShape(50)).background(tint))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val line = arrived ?: account.granted?.takeIf { it > 0.005 }?.let { "含赠送 ${money(account.currency, it)}" }.orEmpty()
                        AnimatedContent(line, Modifier.weight(1f), transitionSpec = {
                            fadeIn(tween(240, easing = Curves.decel)).togetherWith(fadeOut(tween(140, easing = Curves.accel)))
                        }, label = "usageLine") { t -> Txt(t, if (t == arrived) Skin.accent else Page.muted, 12.sp, maxLines = 1) }
                        if (DeepSeekPay.supports(config)) Pressable({ DeepSeekPay.open(account.remaining) }, label = "充值") { pressed ->
                            Txt("充值", Skin.onAccent, 14.sp, Modifier.graphicsLayer { scaleX = if (pressed) .95f else 1f; scaleY = scaleX }
                                .clip(RoundedCornerShape(50)).background(Skin.accent).padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private val chevDown = Ic("M6 9l6 6 6-6")

/** 手动查看各家余额：打开时把能查的都刷一遍。 */
@Composable
private fun ProviderBalances(store: AssistantStore, current: String, onPick: (String) -> Unit) {
    val list = PROVIDERS.filter { store.config(it.id).configured }
    val revision by ModelUsage.revision.collectAsState()
    LaunchedEffect(Unit) { list.forEach { p -> val c = store.config(p.id); if (ModelUsage.canRefresh(c)) launch { ModelUsage.refresh(p, c) } } }
    KSheetTitle("账户余额")
    list.forEach { p ->
        val c = store.config(p.id)
        val account = remember(p.id, revision) { ModelUsage.snapshot(ModelUsage.accountKey(p, c), "", System.currentTimeMillis()).account }
        Pressable({ onPick(p.id) }, Modifier.fillMaxWidth().padding(bottom = 8.dp), label = c.name.ifBlank { p.name }) { pressed ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (pressed) K.pill else K.card2)
                    .then(if (p.id == current) Modifier.border(1.5.dp, Skin.accent, RoundedCornerShape(14.dp)) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(p.id, p.letter, 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt(c.name.ifBlank { p.name }, K.ink, 15.sp, Modifier.weight(1f), maxLines = 1)
                    Txt(
                        when {
                            account == null -> if (ModelUsage.canRefresh(c)) "…" else "不支持查询"
                            account.full != null -> "${money(account.currency, account.remaining)} / ${money(account.currency, account.full!!)}"
                            else -> money(account.currency, account.remaining)
                        }, K.sub, 13.sp, maxLines = 1,
                    )
                }
                account?.fraction?.let { f ->
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(K.line)) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (account.full != null && account.remaining / account.full!! < .15) usageWarning else Skin.accent))
                    }
                }
            }
        }
    }
}

/* ---------- Token 统计 ---------- */

private val mixInput = Color(0xFF3FA35E)
private val mixCached = Color(0xFF6F83EA)
private val mixOutput = Color(0xFFCF763A)

private fun short(n: Long): String = when {
    n >= 1_000_000 -> "%.2fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}

private fun md(day: Long): String = LocalDate.ofEpochDay(day).let { "${it.monthValue}/${it.dayOfMonth}" }

/** 一根柱子：一天，或「全部」里的一周。 */
private data class Bucket(val from: Long, val to: Long, val cost: Double, val tokens: Long)

@Composable
private fun StatsCard(activeModel: String, revision: Long) {
    var range by remember { mutableIntStateOf(7) }
    val today = LocalDate.now().toEpochDay()
    val from = when (range) { 7 -> today - 6; 30 -> today - 29; else -> minOf(ModelUsage.firstDay(), today - 6) }
    val stats = remember(range, revision, today) { ModelUsage.stats(from) }
    val weekly = stats.days.size > 60
    val buckets = remember(stats) {
        if (!weekly) stats.days.map { Bucket(it.day, it.day, it.cost, it.input + it.cached + it.output) }
        else stats.days.reversed().chunked(7).map { w -> Bucket(w.last().day, w.first().day, w.sumOf { it.cost }, w.sumOf { it.input + it.cached + it.output }) }.reversed()
    }
    var picked by remember(range) { mutableIntStateOf(-1) }
    val priced = stats.currency != null
    Column(Modifier.fillMaxWidth().background(veil(.06f), RoundedCornerShape(18.dp)).padding(14.dp)) {
        RangeSeg(range) { range = it }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val hero by animateFloatAsState(if (priced) stats.cost.toFloat() else (stats.input + stats.cached + stats.output).toFloat(), tween(460, easing = Curves.standard), label = "usageHero")
            Txt(if (priced) money(stats.currency!!, hero.toDouble()) else "${short(hero.toLong())} Token", Page.ink, 28.sp, Modifier.weight(1f), maxLines = 1)
            Txt("${count(stats.requests)} 次请求", Page.muted, 13.sp, Modifier.padding(bottom = 4.dp))
        }
        // 输入 / 缓存 / 输出 构成：三段之间留 2dp 缝
        val total = (stats.input + stats.cached + stats.output).coerceAtLeast(1L).toFloat()
        val wi by animateFloatAsState(stats.input / total, tween(460, easing = Curves.standard), label = "mixI")
        val wc by animateFloatAsState(stats.cached / total, tween(460, easing = Curves.standard), label = "mixC")
        val wo by animateFloatAsState(stats.output / total, tween(460, easing = Curves.standard), label = "mixO")
        Row(Modifier.padding(top = 12.dp, bottom = 10.dp).fillMaxWidth().height(8.dp).semantics {
            contentDescription = "输入 ${count(stats.input)}，缓存 ${count(stats.cached)}，输出 ${count(stats.output)} Token"
        }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (stats.input + stats.cached + stats.output == 0L) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(veil(.08f)))
            else listOf(wi to mixInput, wc to mixCached, wo to mixOutput).forEach { (w, c) ->
                if (w > .001f) Box(Modifier.weight(w).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(mixInput, "输入", stats.input)
            Legend(mixCached, "缓存", stats.cached)
            Legend(mixOutput, "输出", stats.output)
        }
        // 每天（或每周）的花费；没单价时按 Token
        val values = buckets.map { if (priced) it.cost else it.tokens.toDouble() }
        val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val sel = if (picked in buckets.indices) picked else buckets.lastIndex
        Row(Modifier.padding(top = 18.dp).fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            buckets.forEachIndexed { i, b ->
                val target = if (values[i] <= 0) .04f else (values[i] / max).toFloat().coerceAtLeast(.04f)
                val h by animateFloatAsState(target, tween(460, delayMillis = (i * 300 / buckets.size.coerceAtLeast(1)), easing = Curves.standard), label = "col")
                val a by animateFloatAsState(if (i == sel) 1f else .5f, tween(150), label = "colA")
                Pressable({ picked = i }, Modifier.weight(1f).fillMaxHeight(), label = md(b.from)) { _ ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight().graphicsLayer { scaleY = h; transformOrigin = TransformOrigin(.5f, 1f); alpha = a }
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
                            .background(if (values[i] <= 0) veil(.1f) else Skin.accent))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            buckets.firstOrNull()?.let { Txt(md(it.from), Page.muted, 11.sp) }
            Spacer(Modifier.weight(1f))
            Txt(if (weekly) "本周" else "今天", Page.muted, 11.sp)
        }
        buckets.getOrNull(sel)?.let { b ->
            val day = when { weekly -> "${md(b.from)} 那周"; b.from == today -> "今天"; else -> md(b.from) }
            val tip = if (priced) "$day · ${money(stats.currency!!, b.cost)} · ${short(b.tokens)} Token" else "$day · ${short(b.tokens)} Token"
            AnimatedContent(tip, transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) }, label = "usageTip") { t ->
                Txt(t, Page.muted, 12.sp, Modifier.padding(top = 8.dp), maxLines = 1)
            }
        }
        // 按模型
        if (stats.models.isNotEmpty()) {
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(veil(.06f)))
            val top = stats.models.maxOf { if (priced) it.cost else it.tokens.toDouble() }.coerceAtLeast(1e-9)
            stats.models.take(8).forEach { m ->
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(m.model, Page.ink, 14.sp, Modifier.weight(1f, fill = false), maxLines = 1)
                        if (m.model == activeModel) Box(Modifier.padding(start = 6.dp).size(6.dp).clip(RoundedCornerShape(50)).background(Skin.accent))
                        Spacer(Modifier.weight(1f))
                        Txt(if (priced && m.cost > 0) money(stats.currency!!, m.cost) else "${short(m.tokens)} Token", Page.muted, 13.sp, maxLines = 1)
                    }
                    val f by animateFloatAsState(((if (priced) m.cost else m.tokens.toDouble()) / top).toFloat(), tween(460, easing = Curves.standard), label = "mdl")
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(veil(.08f))) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = f; transformOrigin = TransformOrigin(0f, .5f) }.clip(RoundedCornerShape(50)).background(Skin.accent))
                    }
                    if (priced && m.cost > 0) Txt("${short(m.tokens)} Token", Page.muted, 11.sp, Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, name: String, n: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Txt(" $name ", Page.muted, 12.sp)
        Txt(short(n), Page.ink, 12.sp)
    }
}

/** 7 天 / 30 天 / 全部：底块平移到选中项。 */
@Composable
private fun RangeSeg(range: Int, onPick: (Int) -> Unit) {
    val options = listOf(7 to "7 天", 30 to "30 天", 0 to "全部")
    val idx = options.indexOfFirst { it.first == range }.coerceAtLeast(0)
    val x by animateFloatAsState(idx.toFloat(), tween(320, easing = Curves.standard), label = "seg")
    BoxWithConstraints(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(veil(.06f)).padding(3.dp)) {
        val w = maxWidth / 3
        Box(Modifier.width(w).fillMaxHeight().graphicsLayer { translationX = x * w.toPx() }.clip(RoundedCornerShape(15.dp)).background(veil(.12f)))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (v, label) ->
                Pressable({ onPick(v) }, Modifier.weight(1f).fillMaxHeight(), label = label) { pressed ->
                    Txt(label, if (v == range) Page.ink else Page.muted, 13.sp, Modifier.graphicsLayer { scaleX = if (pressed) .95f else 1f; scaleY = scaleX })
                }
            }
        }
    }
}
