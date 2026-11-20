package com.fuguang.lueying

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 拆书：把小说拆成章节、角色卡、人物之间的态度与对白、世界背景（页面在 assets/chaishu，ChaishuPage.kt 托着）。
 *
 * 分析结果每本书存一份 JSON（filesDir/chaishu/<id>.json），原文另存 <id>.txt 给查原文用。
 * 用不用照 Kelivo 的规矩：在聊天 ＋ › 拆书 里按助理勾；助理开着「独立对话指令注入」时按对话勾（和指令注入同一套）。
 * 勾上的书：最近几句提到书里的人名或称呼，这人的角色卡自动放进系统提示（Kelivo 世界书的关键词触发）；
 * 另给四个工具，写作时主动查角色卡、人物关系、原文、世界背景。
 */
object Chaishu {
    private const val ASST_KEY = "chaishu-assistant"
    private const val CONV_KEY = "chaishu-conv"
    private var prefs: Prefs? = null
    private var dir: File? = null

    /** 书架上的一本：列表、＋ 面板里显示用。 */
    class Shelf(val id: String, val title: String, val chapters: Int, val people: Int, val done: Boolean, val unit: String)

    val books = mutableStateListOf<Shelf>()
    private val asst = mutableStateMapOf<String, List<String>>()
    private val conv = mutableStateMapOf<String, List<String>>()

    /** 拆书页开着（App.kt 的 ChaishuLayer 盖在最上面）。 */
    var open by mutableStateOf(false)

    fun init(ctx: Context, p: Prefs) {
        prefs = p
        dir = File(ctx.filesDir, "chaishu").apply { mkdirs() }
        asst.clear(); conv.clear()
        runCatching { readMap(p.string(ASST_KEY), asst) }
        runCatching { readMap(p.string(CONV_KEY), conv) }
        refresh()
    }

    private fun readMap(s: String?, into: MutableMap<String, List<String>>) {
        val o = JSONObject(s ?: "{}"); o.keys().forEach { k -> val a = o.getJSONArray(k); into[k] = (0 until a.length()).map { a.getString(it) } }
    }
    private fun writeMap(m: Map<String, List<String>>) = JSONObject(m.mapValues { JSONArray(it.value) }).toString()
    private fun saveSel() { prefs?.putString(ASST_KEY, writeMap(asst)); prefs?.putString(CONV_KEY, writeMap(conv)) }

    /* ---------- 存取（页面通过 ChaishuPage 的桥调这些） ---------- */

    private val ID = Regex("[A-Za-z0-9_-]{1,40}")
    fun okId(id: String) = ID.matches(id)
    fun jsonFile(id: String): File? = dir?.let { d -> if (okId(id)) File(d, "$id.json") else null }
    fun textFile(id: String): File? = dir?.let { d -> if (okId(id)) File(d, "$id.txt") else null }

    /** 读一遍目录，按改动时间新的在前。 */
    fun refresh() {
        val d = dir ?: return
        val list = (d.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.lastModified() }.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                val ch = o.optJSONArray("chapters")?.length() ?: 0
                Shelf(f.name.removeSuffix(".json"), o.optString("title"), ch, o.optJSONArray("order")?.length() ?: 0, o.optBoolean("done"), o.optString("unit", "章"))
            }.getOrNull()
        }
        books.clear(); books.addAll(list)
        cache.keys.retainAll(list.map { it.id }.toSet())
    }

    fun list(): String {
        val d = dir ?: return "[]"
        val a = JSONArray()
        (d.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.lastModified() }.forEach { f ->
            runCatching { a.put(JSONObject(f.readText())) }
        }
        return a.toString()
    }

    fun save(id: String, json: String): Boolean {
        val f = jsonFile(id) ?: return false
        if (runCatching { JSONObject(json) }.isFailure) return false
        val tmp = File(f.parentFile, "$id.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        refresh()
        return true
    }

    fun remove(id: String) {
        jsonFile(id)?.delete(); textFile(id)?.delete()
        asst.keys.toList().forEach { k -> asst[k] = asst[k].orEmpty() - id }
        conv.keys.toList().forEach { k -> conv[k] = conv[k].orEmpty() - id }
        saveSel(); refresh()
    }

    /* ---------- 勾选：和指令注入同一套范围 ---------- */

    fun scoped(p: Persona, convId: String?) = convId != null && AssistantExt.of(p).perConvInject
    fun picked(p: Persona, convId: String?): List<String> =
        (if (scoped(p, convId)) conv[convId].orEmpty() else asst[p.id].orEmpty()).filter { id -> books.any { it.id == id } }
    fun toggle(p: Persona, convId: String?, id: String) {
        val cur = picked(p, convId)
        val next = if (id in cur) cur - id else cur + id
        if (scoped(p, convId)) conv[convId!!] = next else asst[p.id] = next
        saveSel()
    }
    fun count(p: Persona, convId: String?) = picked(p, convId).size

    /** 「我的」那一行的副标题。 */
    fun mineSub(p: Persona): String {
        if (books.isEmpty()) return "导入小说，分析人物与世界"
        val on = picked(p, null).mapNotNull { id -> books.firstOrNull { it.id == id } }
        return if (on.isEmpty()) "在对话的 ＋ 里选书" else "${p.name} 写作时调用《${on[0].title}》" + if (on.size > 1) " 等 ${on.size} 本" else ""
    }

    /* ---------- 聊天里用 ---------- */

    private val cache = HashMap<String, Pair<Long, JSONObject>>()
    private fun load(id: String): JSONObject? {
        val f = jsonFile(id) ?: return null
        if (!f.isFile) return null
        val t = f.lastModified()
        cache[id]?.let { if (it.first == t) return it.second }
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        cache[id] = t to o
        return o
    }

    /** 这一轮能用的书（勾上的、读得出来的）。 */
    suspend fun usable(p: Persona, convId: String?): List<JSONObject> = withContext(Dispatchers.IO) {
        picked(p, convId).mapNotNull { load(it) }
    }

    const val CHAR = "book_character"
    const val REL = "book_relation"
    const val FIND = "book_search"
    const val WORLD = "book_world"
    val names = setOf(CHAR, REL, FIND, WORLD)

    private fun obj(vararg props: Pair<String, String>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, d) -> put(k, JSONObject().put("type", "string").put("description", d)) } })
        .also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) }

    private const val BOOK_ARG = "书名；只勾了一本时可以不填"
    val specs = listOf(
        ToolSpec(CHAR, "看拆书里一个人物的完整角色卡：别名与称呼、性别年龄身高、身份背景、身材外貌、性格，以及对不同的人的态度一览。写到这个人之前先看。",
            obj("name" to "人物的名字、别名或称呼", "book" to BOOK_ARG, required = listOf("name"))),
        ToolSpec(REL, "看一个人怎样对待另一个人或一群人：态度、为什么这样对待、说话方式、前后变化、原文对白。写两人的对手戏、写对白之前用。target 可以是人名，也可以是群体（下人、同学、员工）；不填就列出这人的所有关系。",
            obj("name" to "说话、行动的这一方", "target" to "对方：人名或群体，可空", "book" to BOOK_ARG, required = listOf("name"))),
        ToolSpec(FIND, "在小说原文里查一句话、一个场景或一个词，返回原句和所在章节。要引原文、核对细节、模仿原文笔法时用。多个关键词用空格分开。",
            obj("query" to "要查的词句", "book" to BOOK_ARG, required = listOf("query"))),
        ToolSpec(WORLD, "看这本书的世界背景（时代、地点、阶层、风俗）和章节梗概。",
            obj("book" to BOOK_ARG)),
    )

    fun system(books: List<JSONObject>): String {
        val list = books.joinToString("、") { b -> "《${b.optString("title")}》（${b.optJSONArray("order")?.length() ?: 0} 人 · ${b.optJSONArray("chapters")?.length() ?: 0} ${b.optString("unit", "章")}）" }
        return "拆书分析好的 $list 在你手边。写到书里的人物时先用 $CHAR 看角色卡；写两人对手戏或对白前用 $REL 看这人怎样对待对方、为什么、怎么说话；要贴原文笔法或核对细节用 $FIND 查原句；需要时代与环境用 $WORLD。" +
            "写出来的人物要符合角色卡：出身、地位和过往决定他对不同的人的态度，说话方式也随对象不同。不要把查到的卡片原样念给他听。"
    }

    private fun bookOf(books: List<JSONObject>, title: String): JSONObject? =
        if (title.isBlank()) books.firstOrNull() else books.firstOrNull { it.optString("title").contains(title.trim()) || title.contains(it.optString("title")) } ?: books.firstOrNull()

    private fun unit(b: JSONObject) = b.optString("unit", "章")
    private fun chLabel(b: JSONObject, n: Int) = "第${n}${unit(b)}"
    private fun clean(s: String) = s.replace("<q>", "「").replace("</q>", "」").replace(Regex("<[^>]+>"), "").trim()

    /** 名字、别名、称呼都算；返回角色键。 */
    private fun findChar(b: JSONObject, q: String): String? {
        val chars = b.optJSONObject("chars") ?: return null
        val s = q.trim(); if (s.isEmpty()) return null
        var hit: String? = null
        chars.keys().forEach { k ->
            if (hit != null) return@forEach
            val c = chars.optJSONObject(k) ?: return@forEach
            if (aliases(c).any { it == s }) hit = k
        }
        if (hit == null) chars.keys().forEach { k ->
            if (hit != null) return@forEach
            val c = chars.optJSONObject(k) ?: return@forEach
            if (aliases(c).any { it.length >= 2 && (s.contains(it) || it.contains(s)) }) hit = k
        }
        return hit
    }

    private fun aliases(c: JSONObject): List<String> {
        val out = mutableListOf(c.optString("n"))
        c.optJSONArray("al")?.let { a -> for (i in 0 until a.length()) out += a.optString(i) }
        c.optJSONArray("ti")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.optString(0)?.let { out += it } }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun relName(b: JSONObject, r: JSONObject): String =
        r.optString("g").ifBlank { b.optJSONObject("chars")?.optJSONObject(r.optString("to"))?.optString("n") ?: r.optString("to") }

    /** 一张角色卡的文字版（给模型看）。brief：自动注入时用短版。 */
    fun card(b: JSONObject, key: String, brief: Boolean = false): String {
        val c = b.optJSONObject("chars")?.optJSONObject(key) ?: return ""
        val sb = StringBuilder("【${c.optString("n")}】${c.optString("r")}")
        val al = c.optJSONArray("al")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val ti = c.optJSONArray("ti")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { x -> x.optString(0) + "（" + x.optString(1) + "这样叫）" } }.orEmpty()
        if (al.isNotEmpty() || ti.isNotEmpty()) sb.append("\n别名与称呼：").append((al + ti).joinToString("、"))
        c.optJSONObject("fx")?.let { fx ->
            val L = listOf("gender" to "性别", "age" to "年龄", "height" to "身高")
            sb.append("\n").append(L.joinToString("｜") { (k, l) ->
                val f = fx.optJSONArray(k)
                if (f == null || f.optString(0).isBlank()) "$l：原文未写" else "$l：${f.optString(0)}" + if (f.optString(1) == "推断") "（推断）" else ""
            })
        }
        listOf("bg" to "身份背景", "build" to "身材外貌", "pers" to "性格").forEach { (k, l) ->
            val f = c.optJSONObject(k) ?: return@forEach
            sb.append("\n$l：").append(clean(f.optString("v")))
            if (!brief && f.optString("d").isNotBlank()) sb.append(" ").append(clean(f.optString("d")))
        }
        val rels = c.optJSONArray("rels")
        if (rels != null && rels.length() > 0) {
            sb.append("\n对待不同的人：")
            for (i in 0 until rels.length()) {
                val r = rels.optJSONObject(i) ?: continue
                sb.append("\n- 对${relName(b, r)}（${r.optString("type")}）：").append(clean(if (brief) r.optString("v") else r.optString("why").ifBlank { r.optString("v") }))
                if (r.optString("tone").isNotBlank()) sb.append(" 说话方式：").append(r.optString("tone"))
            }
        } else c.optJSONArray("rel")?.let { a ->
            if (a.length() > 0) sb.append("\n关系：").append((0 until a.length()).mapNotNull { a.optJSONArray(it) }.joinToString("、") { x ->
                (b.optJSONObject("chars")?.optJSONObject(x.optString(0))?.optString("n") ?: x.optString(0)) + "（" + x.optString(1) + "）"
            })
        }
        c.optJSONArray("ch")?.let { a -> if (a.length() > 0) sb.append("\n出场：").append((0 until a.length()).joinToString("、") { chLabel(b, a.optInt(it)) }) }
        return sb.toString()
    }

    private fun relation(b: JSONObject, key: String, target: String): String {
        val c = b.optJSONObject("chars")?.optJSONObject(key) ?: return ""
        val rels = c.optJSONArray("rels") ?: return card(b, key)
        val t = target.trim()
        val tKey = if (t.isEmpty()) null else findChar(b, t)
        val hits = (0 until rels.length()).mapNotNull { rels.optJSONObject(it) }.filter { r ->
            t.isEmpty() || (tKey != null && r.optString("to") == tKey) || r.optString("g").let { g -> g.isNotBlank() && (g.contains(t) || t.contains(g)) }
        }
        if (hits.isEmpty()) return "${c.optString("n")}的关系里没有「$t」。现有：" + (0 until rels.length()).mapNotNull { rels.optJSONObject(it) }.joinToString("、") { relName(b, it) }
        if (t.isEmpty()) return "${c.optString("n")}对待不同的人：\n" + hits.joinToString("\n") { r -> "- ${relName(b, r)}（${r.optString("type")}）：${clean(r.optString("v"))}" }
        return hits.joinToString("\n\n") { r ->
            val sb = StringBuilder("${c.optString("n")} → ${relName(b, r)}（${r.optString("type")}）")
            sb.append("\n为什么这样对待：").append(clean(r.optString("why").ifBlank { r.optString("v") }))
            if (r.optString("tone").isNotBlank()) sb.append("\n说话方式：").append(r.optString("tone"))
            r.optJSONArray("att")?.let { a ->
                if (a.length() > 0) sb.append("\n前后变化：")
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> sb.append("\n- ${chLabel(b, x.optInt("c"))}：${clean(x.optString("v"))}。${clean(x.optString("d"))}") }
            }
            r.optJSONArray("sc")?.let { a ->
                if (a.length() > 0) sb.append("\n原文对白：")
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> sb.append("\n- ${x.optString("ctx")}「${x.optString("q")}」——${x.optString("who")}，${chLabel(b, x.optInt("c"))}") }
            }
            sb.toString()
        }
    }

    private fun world(b: JSONObject): String {
        val sb = StringBuilder("《${b.optString("title")}》")
        b.optJSONObject("world")?.let { w ->
            sb.append(" 世界：").append(w.optString("sum"))
            w.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { x -> sb.append("\n- ${x.optString(0)}：${x.optString(1)}") } }
        }
        b.optJSONArray("chapters")?.let { a ->
            sb.append("\n章节：")
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
                val t = x.optJSONArray("t")?.let { tt -> (0 until tt.length()).map { tt.optString(it) }.filter { it.isNotBlank() }.joinToString(" ") }.orEmpty()
                sb.append("\n${chLabel(b, i + 1)} $t").append(x.optString("sum").let { if (it.isBlank()) "" else "：$it" })
            }
        }
        return sb.toString().take(12000)
    }

    /** 查原文：所有词都在同一段里才算；最多 8 段。 */
    private fun search(b: JSONObject, q: String): String {
        val f = textFile(b.optString("id")) ?: return "这本书没存原文。"
        if (!f.isFile) return "这本书没存原文。"
        val words = q.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return "要查什么？"
        val starts = b.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optInt("line", 0) ?: 0 } }.orEmpty()
        val hits = mutableListOf<String>()
        var n = 0
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                n++
                if (words.all { line.contains(it) }) {
                    val ch = starts.indexOfLast { it <= n - 1 }.let { if (it < 0) 0 else it + 1 }
                    val s = line.trim()
                    val at = s.indexOf(words[0]).coerceAtLeast(0)
                    val cut = if (s.length <= 220) s else s.substring((at - 90).coerceAtLeast(0)).take(220)
                    hits += (if (ch > 0) chLabel(b, ch) + "：" else "") + cut
                    if (hits.size >= 8) break
                }
            }
        }
        return if (hits.isEmpty()) "原文里没找到「$q」。" else hits.joinToString("\n")
    }

    suspend fun run(n: String, a: JSONObject, books: List<JSONObject>): ToolOut = withContext(Dispatchers.IO) {
        val b = bookOf(books, a.optString("book")) ?: return@withContext ToolOut("这个对话没勾拆书里的书。", failed = true)
        when (n) {
            CHAR -> {
                val k = findChar(b, a.optString("name"))
                if (k == null) ToolOut("《${b.optString("title")}》里没有「${a.optString("name")}」。书里的人物：" + (b.optJSONArray("order")?.let { o -> (0 until o.length()).take(40).joinToString("、") { b.optJSONObject("chars")?.optJSONObject(o.optString(it))?.optString("n").orEmpty() } } ?: ""))
                else ToolOut(card(b, k))
            }
            REL -> {
                val k = findChar(b, a.optString("name"))
                if (k == null) ToolOut("《${b.optString("title")}》里没有「${a.optString("name")}」。", failed = true)
                else ToolOut(relation(b, k, a.optString("target")))
            }
            FIND -> ToolOut(search(b, a.optString("query")))
            WORLD -> ToolOut(world(b))
            else -> ToolOut("没有这个工具：$n", failed = true)
        }
    }

    /**
     * 照 Kelivo 世界书：世界背景一直放；最近 4 句里出现的人名、别名、称呼（两个字以上）触发这人的角色卡。
     * 一轮最多 4 张短卡，放在系统提示末尾。
     */
    fun lore(books: List<JSONObject>, recent: List<ChatMsg>): String {
        if (books.isEmpty()) return ""
        val scan = recent.takeLast(4).joinToString("\n") { it.text }
        val sb = StringBuilder()
        books.forEach { b ->
            b.optJSONObject("world")?.optString("sum")?.takeIf { it.isNotBlank() }?.let { sb.append("\n《${b.optString("title")}》的世界：").append(it) }
            val chars = b.optJSONObject("chars") ?: return@forEach
            val order = b.optJSONArray("order")?.let { o -> (0 until o.length()).map { o.optString(it) } } ?: chars.keys().asSequence().toList()
            order.filter { k -> chars.optJSONObject(k)?.let { c -> aliases(c).any { it.length >= 2 && scan.contains(it) } } == true }.take(4).forEach { k ->
                sb.append("\n\n").append(card(b, k, brief = true))
            }
        }
        return if (sb.isEmpty()) "" else "\n\n（拆书里的资料，写到这些人时照着来）" + sb.toString().take(9000)
    }
}
