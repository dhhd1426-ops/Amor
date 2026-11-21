package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 她要改世界书里已有的条目，先在聊天里问你（「改 / 先不改」）。 */
class WbAsk(val book: String, val names: List<String>) {
    val answer = CompletableDeferred<Boolean>()
}

/**
 * 写世界书（原型 J4db4TpEL2Hr6EposKy8xJ，方案 A）：她直接把设定写进 App 的世界书，不再做成 SillyTavern 文件让你导入。
 * 书名没有就新建；同名条目算改，改之前先问你；新条目直接加。写完把这本勾到这个对话上。
 * 聊天里回复上方一张世界书卡（引用 "wb:<书 id>|<这次做了什么>"），点开跳到世界书页，展开这本、刚写的条目亮一下。
 */
object WbTools {
    const val WRITE = "write_world_book"
    const val PREFIX = "wb:"

    /** 正等你点头的那一次（聊天里显示「改 / 先不改」）。 */
    var asking by mutableStateOf<WbAsk?>(null)

    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

    val spec = ToolSpec(
        WRITE,
        "把人物、场景、规则等设定直接写进 App 的世界书（不要做成文件让他导入）。书名没有就新建一本；条目名和已有条目相同就是改那一条（会先问他），不同就是新加。" +
            "每条写清名字、触发关键词和内容；写法规则、世界设定这类一直要带着的设 constant=true，其余靠关键词触发。写完这本会自动勾到当前对话上。",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("book", str("世界书的名字"))
            .put("description", str("这本书的简介，新建时用；可不填"))
            .put("entries", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("name", str("条目名，如「王慧兰 · 外形」"))
                .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "提到哪些词时带上这条；常驻条目可以不填"))
                .put("content", str("条目内容，写全"))
                .put("constant", JSONObject().put("type", "boolean").put("description", "一直带着，不靠关键词"))
                .put("kind", str("可选：人物 / 关系 / 世界 / 剧情"))
            ).put("required", JSONArray(listOf("name", "content")))))
        ).put("required", JSONArray(listOf("book", "entries"))),
    )

    fun name(ref: String) = ref.removePrefix(PREFIX).substringAfter('|').substringBefore('|')
    fun bookId(ref: String) = ref.removePrefix(PREFIX).substringBefore('|')
    fun summary(ref: String) = ref.removePrefix(PREFIX).substringAfter('|').substringAfter('|').substringBefore('#')
    /** 这次写了哪几条（点卡片时亮起来）。 */
    fun lit(ref: String): Set<String> = ref.substringAfter("#", "").split(',').filter { it.isNotEmpty() }.toSet()

    /** onCard 拿到卡片引用："wb:<书 id>|<书名>|<新建 10 条>#<条目 id,…>"。 */
    suspend fun run(a: JSONObject, who: Persona, convId: String?, onCard: (String) -> Unit): ToolOut {
        val title = a.optString("book").trim().trim('《', '》').replace('|', '｜').replace('#', '＃').take(40)
        if (title.isEmpty()) return ToolOut("book 是空的：先给这本世界书起个名字。", failed = true)
        val arr = a.optJSONArray("entries") ?: JSONArray()
        val want = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val n = o.optString("name").trim().take(60)
            val c = o.optString("content").trim()
            if (n.isEmpty() || c.isEmpty()) null else o
        }
        if (want.isEmpty()) return ToolOut("entries 是空的：每条要有 name 和 content。", failed = true)

        val old = withContext(Dispatchers.Main) { WorldBooks.books.firstOrNull { it.name.trim() == title } }
        val names = old?.entries?.map { it.name.trim() }?.toSet().orEmpty()
        val changing = want.map { it.optString("name").trim() }.filter { it in names }.distinct()
        var allowChange = true
        if (changing.isNotEmpty()) {
            val ask = WbAsk(title, changing)
            withContext(Dispatchers.Main) { asking?.answer?.complete(false); asking = ask }
            allowChange = try { ask.answer.await() } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { if (asking === ask) asking = null }
                throw e
            }
            withContext(Dispatchers.Main) { if (asking === ask) asking = null }
        }

        return withContext(Dispatchers.Main) {
            val base = WorldBooks.books.firstOrNull { it.name.trim() == title }
                ?: WorldBook(WbEntry.newId(), title, a.optString("description").trim().take(400))
            val list = base.entries.toMutableList()
            val touched = mutableListOf<String>()
            var added = 0
            var changed = 0
            for (o in want) {
                val n = o.optString("name").trim().take(60)
                val kws = o.optJSONArray("keywords")?.let { k -> (0 until k.length()).map { k.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
                val kind = o.optString("kind").trim().takeIf { it in WbKinds.all }.orEmpty()
                val constant = o.optBoolean("constant") || kws.isEmpty()
                val i = list.indexOfFirst { it.name.trim() == n }
                if (i >= 0) {
                    if (!allowChange) continue
                    val e = list[i]
                    list[i] = e.copy(content = o.optString("content").trim(), keywords = if (kws.isNotEmpty()) kws else e.keywords,
                        constantActive = if (o.has("constant")) constant else e.constantActive, kind = kind.ifEmpty { e.kind }, matchAll = if (kind.isNotEmpty()) kind == "关系" else e.matchAll)
                    touched += e.id; changed++
                } else {
                    val e = WbEntry(WbEntry.newId() + "-$added", name = n, content = o.optString("content").trim(), keywords = kws, constantActive = constant, kind = kind, matchAll = kind == "关系")
                    list += e; touched += e.id; added++
                }
            }
            val fresh = WorldBooks.get(base.id) == null
            if (touched.isEmpty()) return@withContext ToolOut("他没让改《$title》里的「${changing.joinToString("、")}」，什么都没写。问问他想怎么改。")
            WorldBooks.put(base.copy(entries = list, enabled = true))
            WorldBooks.expand(base.id)
            pick(who, convId, base.id)
            val sum = when {
                fresh -> "新建 ${list.size} 条"
                changed == 0 -> "新增 $added 条"
                added == 0 -> "改了 $changed 条"
                else -> "新增 $added 条，改了 $changed 条"
            }
            onCard("$PREFIX${base.id}|$title|$sum#${touched.joinToString(",")}")
            val skipped = if (!allowChange) "。他没让改「${changing.joinToString("、")}」，这几条保持原样" else ""
            ToolOut("写好了：世界书《$title》$sum$skipped，已经勾到这个对话上，接下来聊的时候会用到。卡片已经在聊天里，不用再贴条目内容，也不要再做成文件。")
        }
    }

    /** 勾到这个对话上：对话单独勾过就加进对话的，否则加进这位助理的。 */
    private fun pick(who: Persona, convId: String?, id: String) {
        val conv = convId?.let { WorldBooks.convPick(it) }
        if (convId != null && conv != null) { if (id !in conv) WorldBooks.setConvPick(convId, conv + id) }
        else {
            val x = AssistantExt.of(who)
            if (id !in x.worldBooks) { x.worldBooks += id; AssistantExt.save() }
        }
    }
}
