package com.fuguang.lueying

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 子助理读书：聊天里发来的书太长、一次带不下时，先问一句，再在后台把书切成几段，几个子助理同时读，
 * 按 [WbKinds] 的模板写出人物 / 关系 / 世界 / 剧情条目，同名条目再合成一份，存成一本「小说世界书」（WorldBook.novel），
 * 并勾给当前助理。之后聊天里一提到谁，世界书按关键词自动带上他的卡。
 * 原文存在 filesDir/novels/<书 id>.txt，助理要引原文、核对细节时用 [FIND] 查原句。
 */
object Novel {
    const val FIND = "book_search"
    val names = setOf(FIND)
    /** 每段多少字：留够模板、示例和输出的地方。 */
    private const val CHUNK = 50_000
    /** 同时读几段。 */
    private const val PARALLEL = 3

    private var dir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "novels").apply { mkdirs() }
        // 拆书模块已删：清掉它留下的书和勾选
        File(ctx.filesDir, "chaishu").takeIf { it.exists() }?.deleteRecursively()
    }

    private fun textFile(bookId: String): File? = dir?.let { d -> if (Regex("[A-Za-z0-9_-]+").matches(bookId)) File(d, "$bookId.txt") else null }

    // ───────── 先问一句 ─────────

    private val TEXTY = setOf("txt", "md", "epub", "docx")

    /** 发来的文件是一本书、而且比这个模型一次能带的长：问要不要读成世界书。 */
    suspend fun maybeOffer(ref: String) {
        val store = AttachLook.store ?: return
        val f = Attach.file(ref)
        if (f.extension.lowercase() !in TEXTY) return
        val text = withContext(Dispatchers.IO) { AttachRead.text(f) } ?: return
        val limit = Attach.textMax(CtxWin.window(store.activeProvider, store.activeModel))
        if (text.length <= limit) return
        val title = Attach.name(ref).substringBeforeLast('.').trim().ifEmpty { "未命名小说" }
        AppSheet.open { OfferSheet(title, text) }
    }

    /** 粗估花费（按 deepseek-flash：输入 ¥2、输出 ¥8 每百万 Token，中文约一字 0.7 Token）。 */
    private fun estimate(chars: Int): Double {
        val parts = (chars + CHUNK - 1) / CHUNK
        val input = chars * 0.7 + parts * 9_000
        val output = parts * 6_000.0 * 1.6
        return (input * 2 + output * 8) / 1_000_000
    }

    @androidx.compose.runtime.Composable
    private fun OfferSheet(title: String, text: String) {
        var role by remember { mutableStateOf("") }
        KSheetTitle("读成世界书")
        Txt("《$title》约 ${"%.1f".format(text.length / 10_000.0)} 万字 · 预计 ¥${"%.1f".format(estimate(text.length).coerceAtLeast(.1))}", K.sub, 13.sp, Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        KField(role, { role = it.take(20) }, hint = "你在书里是谁（不扮演就空着）")
        Spacer(Modifier.height(16.dp))
        KButton("开始读", primary = true) { AppSheet.close(); start(title, text, role.trim(), Memory.active) }
        Spacer(Modifier.height(8.dp))
        KButton("不用了") { AppSheet.close() }
    }

    // ───────── 读 ─────────

    private fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(text.length, i + CHUNK)
            if (end < text.length) text.lastIndexOf('\n', end).takeIf { it > i + CHUNK / 2 }?.let { end = it + 1 }
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    private suspend fun putBook(b: WorldBook) = withContext(Dispatchers.Main) { WorldBooks.put(b) }

    fun start(title: String, text: String, role: String, who: Persona) {
        val id = WbEntry.newId().replace(Regex("[^A-Za-z0-9_-]"), "")
        val parts = split(text)
        WorldBooks.put(WorldBook(id, title, "子助理正在读 0/${parts.size} 段", true, emptyList(), novel = true))
        AssistantExt.of(who).let { x -> if (id !in x.worldBooks) { x.worldBooks += id; AssistantExt.save() } }
        scope.launch {
            textFile(id)?.writeText(text)
            val gate = Semaphore(PARALLEL)
            var done = 0
            val found = parts.mapIndexed { i, part ->
                async {
                    gate.withPermit {
                        val r = readPart(part, i, parts.size, role)
                        synchronized(this@Novel) { done++ }
                        WorldBooks.get(id)?.let { putBook(it.copy(description = "子助理正在读 $done/${parts.size} 段")) }
                        r
                    }
                }
            }.awaitAll()
            val failed = found.count { it == null }
            // 同类同名的合成一份（按书中先后）
            WorldBooks.get(id)?.let { putBook(it.copy(description = "正在整理条目…")) }
            val groups = found.filterNotNull().flatten().groupBy { it.kind to it.name.trim() }
            val entries = groups.map { (k, list) ->
                async {
                    gate.withPermit {
                        val content = if (list.size == 1) list[0].content else merge(k.first, k.second, list) ?: list.joinToString("\n\n") { it.content }
                        WbEntry(
                            WbEntry.newId(), k.second, true, 0, WbPosition.AFTER_SYSTEM, content.trim(), 4, "user",
                            list.flatMap { it.keywords }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(k.second) },
                            kind = k.first, matchAll = k.first == "关系",
                        )
                    }
                }
            }.awaitAll().sortedBy { WbKinds.all.indexOf(it.kind) }
            val note = if (failed > 0) "有 $failed 段没读成" else ""
            WorldBooks.get(id)?.let { putBook(it.copy(description = note, entries = entries)) }
        }
    }

    private class Found(val kind: String, val name: String, val keywords: List<String>, val content: String)

    private fun guide(): String = WbKinds.all.joinToString("\n\n") { k ->
        "=== ${k}模板 ===\n${WbKinds.template[k]}\n\n=== ${k}示例（另一个故事，只学格式和细致程度）===\n${WbKinds.example[k]}"
    }

    private fun readerSystem(role: String) = """
你是读小说的子助理，负责把书里的设定整理成世界书条目。每次给你全书中的一段。
只根据这一段里写到的内容整理，不要编造；书中未写的写「书中未写」。出处照原文里的「第 x 章」标，看不出章节就写「本段」。
「我」是：${role.ifEmpty { "（不扮演书中人物，「我」指主角）" }}。「身份」一栏写这个人和「我」的关系。

要整理四类条目：
- 人物：这一段里有名有姓、有台词或有描写的人物，每人一条。
- 关系：两人之间有明显的感情、矛盾、交易、把柄时，每一对一条。
- 世界：时代背景、重要地点、势力组织、规矩、重要物品，每样一条。
- 剧情：这一段里发生的大事，按章节一条。
每条的内容严格按对应模板的栏目写，每一栏都要写，写法和细致程度照示例。

${guide()}

只输出 JSON，不要别的文字：
{"entries":[{"kind":"人物","name":"本名","keywords":["本名","所有别称"],"content":"按模板写的全文"}]}
关系条目 name 写「甲 × 乙」，keywords 写 ["甲","乙"]。剧情条目 name 写「第 x 章 · 一句话」，keywords 写这件事里最关键的 2 到 3 个人名或地名。
""".trim()

    private suspend fun ask(system: String, user: String, maxTokens: Int): String? {
        val store = AttachLook.store ?: return null
        val pid = store.activeProvider
        val c = store.config(pid)
        // 子助理默认用便宜的 flash；没有就用当前模型
        val model = c.models.firstOrNull { "flash" in it.lowercase() } ?: store.activeModel
        return runCatching { ChatApi.complete(provider(pid), c, model, system, user, maxTokens) }.getOrNull()
    }

    private suspend fun readPart(part: String, i: Int, n: Int, role: String): List<Found>? {
        repeat(2) {
            val raw = ask(readerSystem(role), "这是全书第 ${i + 1}/$n 段：\n\n$part", 8000) ?: return@repeat
            parse(raw)?.let { return it }
        }
        return null
    }

    private fun parse(raw: String): List<Found>? = runCatching {
        val s = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)
        val a = JSONObject(s).optJSONArray("entries") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val kind = o.optString("kind").trim().takeIf { it in WbKinds.all } ?: return@mapNotNull null
            val name = o.optString("name").trim().ifEmpty { return@mapNotNull null }
            val kw = o.optJSONArray("keywords")?.let { k -> (0 until k.length()).map { k.optString(it) } }.orEmpty()
            Found(kind, name, kw, o.optString("content"))
        }
    }.getOrNull()

    private suspend fun merge(kind: String, name: String, list: List<Found>): String? {
        val system = """
下面是同一个${kind}条目「$name」在一本小说不同段里分别整理的几份，按书中先后排列。把它们合成一份：
- 严格按模板的栏目写，每一栏把几份的内容合在一起，去掉重复，保留出处。
- 前后有变化的（关系、态度、身份），按章节范围写清变化。
- 别称、体现场景都合并，一个都不要丢。
只输出合成后的正文，不要别的文字。

=== 模板 ===
${WbKinds.template[kind]}
""".trim()
        val user = list.mapIndexed { i, f -> "【第 ${i + 1} 份】\n${f.content}" }.joinToString("\n\n")
        return ask(system, user, 8000)?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ───────── 查原文 ─────────

    /** 这一轮勾着的、存了原文的小说世界书。 */
    fun usable(p: Persona, convId: String?): List<WorldBook> =
        WorldBooks.active(p, convId, AssistantExt.of(p).perConvInject).filter { it.novel && textFile(it.id)?.isFile == true }

    private fun obj(vararg props: Pair<String, String>, required: List<String>) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) } })
        .put("required", JSONArray(required))

    val spec = ToolSpec(
        FIND, "在小说原文里查一句话、一个场景或一个词，返回原句和所在章节。要引原文、核对细节、模仿原文笔法时用。多个关键词用空格分开，都出现在同一段里才算。",
        obj("query" to "要查的词句", "book" to "书名；只有一本时可以不填", required = listOf("query")),
    )

    fun system(books: List<WorldBook>): String =
        "${books.joinToString("、") { "《${it.name}》" }}的原文在你手边。书里人物、关系、世界、剧情的设定会在提到时自动放到你眼前，写出来的人物要符合这些设定；要引原文、核对细节、贴原文笔法时用 $FIND 查原句。不要把设定原样念给他听。"

    private val chapter = Regex("^\\s*第[0-9一二三四五六七八九十百千零〇两]+[章回节卷]")

    suspend fun run(a: JSONObject, books: List<WorldBook>): ToolOut = withContext(Dispatchers.IO) {
        val want = a.optString("book").trim().trim('《', '》')
        val b = books.firstOrNull { want.isNotEmpty() && (it.name.contains(want) || want.contains(it.name)) } ?: books.firstOrNull()
            ?: return@withContext ToolOut("这个对话没有勾带原文的小说世界书。", failed = true)
        val f = textFile(b.id)?.takeIf { it.isFile } ?: return@withContext ToolOut("《${b.name}》没存原文。", failed = true)
        val words = a.optString("query").split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return@withContext ToolOut("要查什么？", failed = true)
        val hits = mutableListOf<String>()
        var ch = ""
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                chapter.find(line)?.let { ch = it.value.trim() }
                if (words.all { line.contains(it) }) {
                    val s = line.trim()
                    val at = s.indexOf(words[0]).coerceAtLeast(0)
                    val cut = if (s.length <= 220) s else s.substring((at - 90).coerceAtLeast(0)).take(220)
                    hits += (if (ch.isNotEmpty()) "$ch：" else "") + cut
                    if (hits.size >= 8) break
                }
            }
        }
        ToolOut(if (hits.isEmpty()) "《${b.name}》原文里没找到「${a.optString("query")}」。" else hits.joinToString("\n"))
    }

    /** 删世界书时一起删原文。 */
    fun forget(bookId: String) { textFile(bookId)?.delete() }
}
