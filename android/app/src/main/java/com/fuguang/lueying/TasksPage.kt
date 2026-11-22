package com.fuguang.lueying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「我的 › 定时任务」（第 562 版，原型 XhWSqY67jKfuZFMwLz3Qsm）。
 * 顶上一张卡是「常驻后台」开关（PactKeeper.wanted，prefs `keep-on`）和这台手机还差的授权（Keep.needs，跟约定卡里是同一套）；
 * 下面是任务：你用嘴跟她定的约定和在这里手动加的是同一份（Pacts.all），不做两套。
 * 手动加的两种：定点（时间 + 星期几）、每隔一段（几分钟 / 几小时，可以限定只在几点到几点）；
 * 「她来写」到点问模型现写，「原话发」把你写的字原样发（不问模型、不花钱）。
 * 用嘴定的「几天没聊就来问问」和「某一天一次」这两种这里只能开关，不能改（改了会变成别的种类），要改跟她说。
 * 页面最下面是诊断框：最近十几次到点各发生了什么（Pacts.log）。
 */
@Composable
internal fun TasksPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Pact?>(null) }
    var fresh by remember { mutableStateOf(false) }
    BackHandler(enabled = editing != null) { editing = null }
    LaunchedEffect(Unit) { Pacts.loadLog() }
    val e = editing
    if (e != null) { TaskEdit(e, fresh) { editing = null }; return }

    var keep by remember { mutableStateOf(PactKeeper.wanted(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    val steps = remember(Keep.ver, tick, keep) { if (keep) Keep.needs(ctx) else emptyList() }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        CardPageHead("定时任务", onBack = onBack)
        KCard {
            KRow("常驻后台", sub = if (keep) "划掉界面她也在，到点自己来" else "关着时，划掉界面后任务可能不准时", trailing = {
                KSwitch(keep) { on ->
                    keep = on
                    Prefs(ctx).putString("keep-on", if (on) "1" else "0")
                    PactKeeper.sync(ctx)
                }
            })
            steps.forEach { s -> KRow(s.title, sub = s.note, value = "去授权", divider = true, onClick = { s.go(ctx); tick++ }) }
        }
        if (Pacts.all.isEmpty()) {
            Txt("把任务交给时间", Page.ink.copy(alpha = .8f), 16.sp, Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 26.dp), align = TextAlign.Center, spacing = 1.sp)
        } else KCard {
            Pacts.all.forEachIndexed { i, p ->
                val plain = p.idle == 0 && p.date.isBlank()
                val name = Memory.get(p.who)?.name.orEmpty()
                KRow(
                    p.title.ifBlank { p.what.take(12) },
                    sub = listOf(Pacts.rule(p), name, if (p.raw) "原话" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    divider = i > 0,
                    onClick = if (plain) ({ fresh = false; editing = p }) else null,
                    trailing = { KSwitch(p.on) { on -> Pacts.set(ctx, p.copy(on = on)) } },
                )
            }
        }
        KButton("添加任务") {
            fresh = true
            editing = Pact(Pacts.newId(), Memory.active.id, "", "", "08:00", listOf(1, 2, 3, 4, 5, 6, 7))
        }
        if (Pacts.logText.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            DiagBox(Pacts.logText + "\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · 安卓 " + android.os.Build.VERSION.RELEASE + " · 常驻" + (if (keep) "开" else "关") + " · 还差授权 " + steps.size + " 步")
        }
    }
}

private fun minutesOf(s: String): Int? = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim())?.let { m ->
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

/** 系统的时间选择（24 小时制）。 */
private fun pickTime(ctx: android.content.Context, now: String, onPick: (String) -> Unit) {
    val m = minutesOf(now) ?: 480
    runCatching { android.app.TimePickerDialog(ctx, { _, h, mi -> onPick("%02d:%02d".format(h, mi)) }, m / 60, m % 60, true).show() }
}

@Composable
private fun TaskEdit(p: Pact, fresh: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(p.title) }
    var what by remember { mutableStateOf(p.what) }
    var raw by remember { mutableStateOf(p.raw) }
    var who by remember { mutableStateOf(p.who) }
    var every by remember { mutableStateOf(p.every > 0) }
    var time by remember { mutableStateOf(p.time.ifBlank { "08:00" }) }
    var days by remember { mutableStateOf(if (p.days.isEmpty()) (1..7).toSet() else p.days.toSet()) }
    var hours by remember { mutableStateOf(p.every > 0 && p.every % 60 == 0) }
    var count by remember { mutableStateOf(if (p.every <= 0) "30" else if (p.every % 60 == 0) (p.every / 60).toString() else p.every.toString()) }
    var from by remember { mutableStateOf(p.from.ifBlank { "08:00" }) }
    var to by remember { mutableStateOf(p.to.ifBlank { "23:00" }) }

    val n = (count.toIntOrNull() ?: 0).coerceIn(0, 999)
    val gap = n * (if (hours) 60 else 1)
    val ok = what.isNotBlank() && (if (every) gap >= 1 else days.isNotEmpty())
    // 一天大概来几次：让你看清「每 1 分钟让她现写」是多少次模型调用
    val span = run { val a = minutesOf(from); val b = minutesOf(to); if (a == null || b == null || a == b) 1440 else if (b > a) b - a else b - a + 1440 }
    val perDay = if (gap >= 1) span / gap + 1 else 0

    fun build(): Pact = p.copy(
        title = name.trim().take(12).ifBlank { what.trim().take(8) }, what = what.trim().take(600), who = who, raw = raw,
        time = if (every) "" else time, days = if (every) emptyList() else days.sorted(), date = "", idle = 0,
        every = if (every) gap else 0, from = if (every) from else "", to = if (every) to else "",
    )

    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        CardPageHead(if (fresh) "添加任务" else "改任务", onBack = onDone)
        KLabel("名字")
        KField(name, { name = it.take(12) }, hint = "早报")
        Spacer(Modifier.height(12.dp))
        KSeg(listOf(false to "她来写", true to "原话发"), { it == raw }) { raw = it }
        KLabel(if (raw) "发给你的原话" else "让她做什么")
        KField(what, { what = it }, hint = if (raw) "喝口水，起来走两步。" else "看一下今天的天气，告诉我要不要带伞", lines = 3)

        KLabel("谁来发")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Memory.all.forEach { m ->
                val on = m.id == who
                Pressable({ who = m.id }, label = m.name) { pr ->
                    Txt(m.name, if (on) K.onAcc else K.sub, 13.5.sp, Modifier.background(if (on) K.acc else if (pr) K.pill else K.card2, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
                }
            }
        }

        KLabel("什么时候")
        KSeg(listOf(false to "定点", true to "每隔一段"), { it == every }) { every = it }
        Spacer(Modifier.height(10.dp))
        if (!every) {
            KCard { KRow("时间", value = time, onClick = { pickTime(ctx, time) { time = it } }) }
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, c ->
                    val d = i + 1
                    val on = d in days
                    Pressable({ days = if (on) days - d else days + d }, Modifier.weight(1f), label = "周$c") { pr ->
                        Box(Modifier.fillMaxWidth().height(40.dp).background(if (on) K.acc else if (pr) K.pill else K.card2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Txt(c, if (on) K.onAcc else K.sub, 14.sp, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("每隔", K.ink, 15.sp, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                KField(count, { v -> count = v.filter { ch -> ch.isDigit() }.take(3) }, Modifier.width(88.dp), hint = "30", number = true)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { KSeg(listOf(false to "分钟", true to "小时"), { it == hours }) { hours = it } }
            }
            KCard {
                KRow("从", value = from, onClick = { pickTime(ctx, from) { from = it } })
                KRow("到", value = to, divider = true, onClick = { pickTime(ctx, to) { to = it } })
                KRow("一天", divider = true, trailing = {
                    val heavy = !raw && perDay > 60
                    Txt("约 $perDay 次" + (if (raw) " · 不花钱" else " · 每次都要她想一遍"), if (heavy) K.danger else K.sub, 13.sp, maxLines = 1)
                })
            }
        }

        Box(Modifier.alpha(if (ok) 1f else .45f)) {
            KButton("保存", primary = true) { if (ok) { Pacts.set(ctx, build()); onDone() } }
        }
        if (!fresh) {
            Spacer(Modifier.height(10.dp))
            KButton("现在跑一次") { Pacts.set(ctx, build()); Pacts.runNow(ctx, p.id); onDone() }
            Spacer(Modifier.height(10.dp))
            KButton("删除", color = K.danger) { Pacts.drop(ctx, p.id); onDone() }
        }
    }
}
