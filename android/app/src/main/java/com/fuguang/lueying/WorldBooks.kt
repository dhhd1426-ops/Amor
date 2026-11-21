package com.fuguang.lueying

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书（照 Kelivo 2026-11-19 192a6a8：core/models/world_book.dart、core/services/world_book_activation.dart、
 * message_builder_service.injectWorldBookPrompts）。放在全局设置里，每位助理勾自己用哪几本（AsstExt.worldBooks），
 * 也能只对当前对话勾（ConvWorldBooks）。
 */
enum class WbPosition(val wire: String, val label: String) {
    BEFORE_SYSTEM("BEFORE_SYSTEM_PROMPT", "系统提示前"),
    AFTER_SYSTEM("AFTER_SYSTEM_PROMPT", "系统提示后"),
    TOP("TOP_OF_CHAT", "对话顶部"),
    BOTTOM("BOTTOM_OF_CHAT", "对话底部"),
    DEPTH("AT_DEPTH", "指定深度");
    companion object { fun of(v: String?) = entries.firstOrNull { it.wire == v?.trim()?.uppercase() } ?: AFTER_SYSTEM }
}

data class WbEntry(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val position: WbPosition = WbPosition.AFTER_SYSTEM,
    val content: String = "",
    val injectDepth: Int = 4,
    /** "user" / "assistant" */
    val role: String = "user",
    val keywords: List<String> = emptyList(),
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val scanDepth: Int = 4,
    val constantActive: Boolean = false,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
    /** 小说世界书里的分类：人物 / 关系 / 世界 / 剧情；普通条目为空。 */
    val kind: String = "",
    /** 所有关键词都出现才触发（关系条目：两个人同时被提到）。 */
    val matchAll: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("priority", priority)
        .put("position", position.wire).put("content", content).put("injectDepth", injectDepth).put("role", role.uppercase())
        .put("keywords", JSONArray(keywords)).put("useRegex", useRegex).put("caseSensitive", caseSensitive).put("scanDepth", scanDepth)
        .put("constantActive", constantActive).put("sticky", sticky).put("cooldown", cooldown).put("delay", delay)
        .put("kind", kind).put("matchAll", matchAll)

    companion object {
        fun newId() = "${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}"
        fun fromJson(o: JSONObject): WbEntry {
            val kw = o.optJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
            return WbEntry(
                o.optString("id").ifBlank { newId() }, o.optString("name"), o.optBoolean("enabled", true), o.optInt("priority"),
                WbPosition.of(o.optString("position")), o.optString("content"), o.optInt("injectDepth", 4),
                if (o.optString("role").equals("ASSISTANT", true)) "assistant" else "user", kw,
                o.optBoolean("useRegex"), o.optBoolean("caseSensitive"), o.optInt("scanDepth", 4), o.optBoolean("constantActive"),
                o.optInt("sticky").coerceIn(0, 10000), o.optInt("cooldown").coerceIn(0, 10000), o.optInt("delay").coerceIn(0, 10000),
                o.optString("kind").takeIf { it in WbKinds.all }.orEmpty(), o.optBoolean("matchAll"),
            )
        }
    }
}

/** novel：按人物 / 关系 / 世界 / 剧情分组显示（小说世界书）。 */
data class WorldBook(val id: String, val name: String = "", val description: String = "", val enabled: Boolean = true, val entries: List<WbEntry> = emptyList(), val novel: Boolean = false) {
    val enabledCount get() = entries.count { it.enabled }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("description", description).put("enabled", enabled)
        .put("entries", JSONArray(entries.map { it.toJson() })).put("novel", novel)
    companion object {
        fun fromJson(o: JSONObject) = WorldBook(
            o.optString("id").ifBlank { WbEntry.newId() }, o.optString("name"), o.optString("description"), o.optBoolean("enabled", true),
            o.optJSONArray("entries")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(WbEntry::fromJson) } ?: emptyList(),
            o.optBoolean("novel"),
        )
    }
}

object WorldBooks {
    private const val KEY = "world-books"
    private const val CONV_KEY = "world-books-conv"
    private const val STATE_KEY = "world-books-state"
    private const val FOLD_KEY = "world-books-collapsed"
    private var prefs: Prefs? = null
    val books = mutableStateListOf<WorldBook>()
    /** 对话里「仅当前对话」勾的世界书：对话 id → 书 id 列表。 */
    private val conv = HashMap<String, List<String>>()
    /** 页面上哪几本收起来了（照 Kelivo provider._collapsedBooks，默认展开，记住）。 */
    val collapsed = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** 粘滞 / 冷却的计时：对话 id → {messageCount, historyHash, effects}。 */
    private val state = HashMap<String, JSONObject>()

    fun init(p: Prefs) {
        prefs = p
        runCatching { val a = JSONArray(p.string(KEY) ?: "[]"); books.clear(); for (i in 0 until a.length()) books += WorldBook.fromJson(a.getJSONObject(i)) }
        runCatching { val o = JSONObject(p.string(CONV_KEY) ?: "{}"); o.keys().forEach { k -> val a = o.getJSONArray(k); conv[k] = (0 until a.length()).map { a.getString(it) } } }
        runCatching { val o = JSONObject(p.string(STATE_KEY) ?: "{}"); o.keys().forEach { k -> state[k] = o.getJSONObject(k) } }
        runCatching { val o = JSONObject(p.string(FOLD_KEY) ?: "{}"); o.keys().forEach { k -> if (books.any { it.id == k }) collapsed[k] = o.optBoolean(k) } }
        migrate()
    }

    fun save() { prefs?.putString(KEY, JSONArray(books.map { it.toJson() }).toString()) }
    private fun saveConv() { prefs?.putString(CONV_KEY, JSONObject(conv.mapValues { JSONArray(it.value) }).toString()) }
    private fun saveState() { prefs?.putString(STATE_KEY, JSONObject(state.toMap()).toString()) }

    fun get(id: String) = books.firstOrNull { it.id == id }
    fun put(b: WorldBook) { val i = books.indexOfFirst { it.id == b.id }; if (i >= 0) books[i] = b else books += b; save() }
    fun remove(id: String) { books.removeAll { it.id == id }; collapsed.remove(id); save(); saveFold() }

    fun isCollapsed(id: String) = collapsed[id] == true
    fun toggleCollapsed(id: String) { collapsed[id] = !isCollapsed(id); saveFold() }
    fun expand(id: String) { if (isCollapsed(id)) { collapsed[id] = false; saveFold() } }
    private fun saveFold() { prefs?.putString(FOLD_KEY, JSONObject(collapsed.filterValues { it }.toMap()).toString()) }

    /** 拖动排书（拖的途中每过一格调一次，松手时 [save]）。 */
    fun moveBook(from: Int, to: Int) { if (from in books.indices && to in books.indices && from != to) books.add(to, books.removeAt(from)) }

    /** 拖动排一本书里的条目。 */
    fun moveEntry(bookId: String, from: Int, to: Int) {
        val b = get(bookId) ?: return
        if (from !in b.entries.indices || to !in b.entries.indices || from == to) return
        put(b.copy(entries = b.entries.toMutableList().apply { add(to, removeAt(from)) }))
    }

    /** 把几条复制 / 移动到另一本（Kelivo showWorldBookEntryTransfer）；复制的换新 id。 */
    fun transfer(fromId: String, toId: String, ids: Set<String>, copy: Boolean): Int {
        val src = get(fromId) ?: return 0
        val dst = get(toId) ?: return 0
        val picked = src.entries.filter { it.id in ids }
        put(dst.copy(entries = dst.entries + picked.map { if (copy) it.copy(id = WbEntry.newId()) else it }))
        if (!copy) put(src.copy(entries = src.entries.filter { it.id !in ids }))
        return picked.size
    }

    /** 这个对话有没有自己勾的（「仅当前对话」）；null = 跟着助理。 */
    fun convPick(convId: String): List<String>? = conv[convId]
    fun setConvPick(convId: String, ids: List<String>?) { if (ids == null) conv.remove(convId) else conv[convId] = ids; saveConv() }

    /** 这一轮实际用哪几本：对话自己勾了就用对话的，否则用助理的。 */
    fun active(p: Persona, convId: String?, allowConv: Boolean = true): List<WorldBook> {
        val ids = convId?.takeIf { allowConv }?.let { conv[it] } ?: AssistantExt.of(p).worldBooks
        return ids.mapNotNull { get(it) }.filter { it.enabled }
    }

    /** 原来每位助理的「条目」（Entries.kt）搬成一本世界书，勾在她名下；只搬一次。 */
    private fun migrate() {
        val done = prefs?.string("world-books-migrated") == "1"
        if (done) return
        Memory.all.forEach { p ->
            if (p.entries.isEmpty()) return@forEach
            val book = WorldBook(
                "entries-${p.id}", "${p.name}的条目", "从助理设置里的「条目」搬过来的", true,
                p.entries.map { e ->
                    val pos = when (e.pos) { "before" -> WbPosition.BEFORE_SYSTEM; "after" -> WbPosition.AFTER_SYSTEM; "top" -> WbPosition.TOP; else -> WbPosition.DEPTH }
                    WbEntry(
                        "${e.id}-wb", e.name, e.on, 0, pos,
                        if (e.voice == "self") "（你心里想：${e.body.trim()}）" else e.body.trim(),
                        e.depth, "user", e.keyList, false, false, 4, e.always,
                    )
                },
            )
            if (get(book.id) == null) books += book
            val x = AssistantExt.of(p)
            if (book.id !in x.worldBooks) x.worldBooks += book.id
        }
        save(); AssistantExt.save()
        prefs?.putString("world-books-migrated", "1")
    }

    // ───────── 触发（WorldBookActivation.evaluate） ─────────

    private fun hash(v: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun fingerprint(history: List<ChatMsg>, n: Int) = hash(history.take(n).joinToString("\u0000") { it.role + "\u0001" + it.text })

    fun matches(e: WbEntry, context: String): Boolean {
        if (!e.enabled) return false
        if (e.constantActive) return true
        if (e.matchAll) {
            val keys = e.keywords.map { it.trim() }.filter { it.isNotEmpty() }
            return keys.isNotEmpty() && keys.all { k -> hit(e, k, context) }
        }
        for (raw in e.keywords) {
            val k = raw.trim()
            if (k.isEmpty()) continue
            if (e.useRegex) {
                val hit = runCatching { Regex(k, if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(context) }.getOrDefault(false)
                if (hit) return true
            } else if (if (e.caseSensitive) context.contains(k) else context.lowercase().contains(k.lowercase())) return true
        }
        return false
    }

    private fun hit(e: WbEntry, k: String, context: String): Boolean =
        if (e.useRegex) runCatching { Regex(k, if (e.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(context) }.getOrDefault(false)
        else if (e.caseSensitive) context.contains(k) else context.lowercase().contains(k.lowercase())

    /** 这一轮触发了哪些条目（按优先级从高到低，同级按书和条目的顺序）；粘滞、冷却、延迟按消息条数算。 */
    fun evaluate(books: List<WorldBook>, history: List<ChatMsg>, convId: String?): List<WbEntry> {
        val count = history.size
        val prev = convId?.let { state[it] } ?: JSONObject()
        val prevCount = prev.optInt("messageCount", 0)
        val continues = prev.optString("historyHash").isNotEmpty() && prevCount in 0..count && prev.optString("historyHash") == fingerprint(history, prevCount)
        val oldEffects = if (continues) prev.optJSONObject("effects") ?: JSONObject() else JSONObject()
        val effects = JSONObject()
        val triggered = mutableListOf<Pair<WbEntry, Int>>()
        val ctxCache = HashMap<Int, String>()
        fun contextFor(depth: Int) = ctxCache.getOrPut(depth) {
            history.filter { it.role == "user" || it.role == "assistant" }.map { it.text.trim() }.filter { it.isNotEmpty() }.takeLast(depth).joinToString("\n")
        }
        var seq = 0
        for (book in books) {
            if (!book.enabled) continue
            for (e in book.entries) {
                val order = seq++
                if (!e.enabled || e.content.isBlank()) continue
                val key = JSONArray(listOf(book.id, e.id)).toString()
                val sig = if (e.sticky > 0 || e.cooldown > 0) hash(e.toJson().toString()) else ""
                val effect = oldEffects.optJSONObject(key)?.takeIf { it.optString("signature") == sig }
                var active = false
                val at = effect?.optInt("activatedAt", -1)?.takeIf { it >= 0 }
                if (at != null && at <= count) {
                    val elapsed = count - at
                    if (elapsed <= e.sticky) { active = true; effects.put(key, effect) }
                    else if (elapsed <= e.sticky + e.cooldown) { effects.put(key, effect); continue }
                }
                if (!active) {
                    if (count < e.delay) continue
                    active = matches(e, contextFor(e.scanDepth.coerceIn(1, 200)))
                    if (active && (e.sticky > 0 || e.cooldown > 0)) effects.put(key, JSONObject().put("signature", sig).put("activatedAt", count))
                }
                if (active) triggered += e to order
            }
        }
        if (convId != null) {
            state[convId] = JSONObject().put("messageCount", count).put("historyHash", if (effects.length() == 0) "" else fingerprint(history, count)).put("effects", effects)
            saveState()
        }
        return triggered.sortedWith(compareByDescending<Pair<WbEntry, Int>> { it.first.priority }.thenBy { it.second }).map { it.first }
    }

    // ───────── 放进去（injectWorldBookPrompts） ─────────

    class Injected(val system: String, val history: List<ChatMsg>)

    private fun join(items: List<WbEntry>) = items.map { it.content.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** 用户角色的条目包在 <system> 里（Kelivo wrapSystemTag），助手角色原样。同一处按角色合成一条。 */
    private fun merged(items: List<WbEntry>, at: Long): List<ChatMsg> =
        items.groupBy { it.role }.mapNotNull { (role, group) ->
            val text = join(group).ifEmpty { return@mapNotNull null }
            if (role == "assistant") ChatMsg("assistant", text, at = at) else ChatMsg("user", "<system>\n$text\n</system>", at = at)
        }

    /** allowConv：助理开着「独立对话指令注入」时，对话里自己勾的世界书才算数。 */
    fun inject(p: Persona, convId: String?, system: String, history: List<ChatMsg>, allowConv: Boolean = false): Injected {
        val books = active(p, convId, allowConv)
        if (books.isEmpty() || history.isEmpty()) return Injected(system, history)
        val hit = evaluate(books, history, convId)
        if (hit.isEmpty()) return Injected(system, history)
        val by = hit.groupBy { it.position }
        var sys = system
        join(by[WbPosition.BEFORE_SYSTEM].orEmpty()).takeIf { it.isNotEmpty() }?.let { sys = if (sys.isEmpty()) it else it + "\n" + sys }
        join(by[WbPosition.AFTER_SYSTEM].orEmpty()).takeIf { it.isNotEmpty() }?.let { sys = if (sys.isEmpty()) it else sys + "\n" + it }
        val out = history.toMutableList()
        val at = history.last().at
        by[WbPosition.TOP]?.let { top ->
            val i = out.indexOfFirst { it.role == "user" }.let { if (it < 0) out.size else it }
            out.addAll(i, merged(top, at))
        }
        by[WbPosition.BOTTOM]?.let { bottom -> out.addAll((out.size - 1).coerceAtLeast(0), merged(bottom, at)) }
        by[WbPosition.DEPTH]?.let { depthItems ->
            val original = out.size
            var inserted = 0
            depthItems.groupBy { it.injectDepth }.toSortedMap(compareByDescending { it }).forEach { (depth, items) ->
                val idx = (original - depth).coerceIn(0, original) + inserted
                val msgs = merged(items, at)
                out.addAll(idx, msgs); inserted += msgs.size
            }
        }
        return Injected(sys, out)
    }

    // ───────── 导入 / 导出（Kelivo 格式，兼容 SillyTavern 的 entries 对象） ─────────

    fun export(b: WorldBook): String = b.toJson().put("format", "kelivo-world-book").toString(2)

    /** 导入结果：书，和多少条用了暂不支持的酒馆功能（已停用）。 */
    class Imported(val book: WorldBook, val unsupported: Int)

    /**
     * 读 Kelivo / RikkaHub 导出的世界书，或 SillyTavern 单独导出的 World Info（照 Kelivo utils/world_book_import.dart）。
     * 酒馆里这边不支持的规则（宏、时效、次要关键词、概率、分组、递归延迟、角色筛选、正则关键词……）不能悄悄放宽触发：保留内容，但停用。
     */
    fun import(raw: String, fileName: String): Imported? = runCatching {
        var o = JSONObject(raw)
        o.optJSONObject("data")?.let { o = it }
        val ents = o.opt("entries")
        if (ents is JSONArray) return@runCatching Imported(fresh(WorldBook.fromJson(o)), 0)
        if (ents !is JSONObject) return@runCatching null
        // 酒馆先按整数键排，再在组装提示词时把同序的倒过来；displayIndex 只管它编辑器里的顺序
        val keys = ents.keys().asSequence().toList()
        val src = keys.withIndex().associate { it.value to it.index }
        fun arrIdx(k: String) = k.toLongOrNull()?.takeIf { it in 0 until 0xffffffffL && it.toString() == k }
        val sorted = keys.sortedWith { x, y ->
            val a = arrIdx(x); val b = arrIdx(y)
            when { a != null && b != null -> a.compareTo(b); a != null -> -1; b != null -> 1; else -> src.getValue(x).compareTo(src.getValue(y)) }
        }
        val depthRoles = HashMap<Int, MutableSet<Int>>()
        keys.forEach { k ->
            val e = ents.optJSONObject(k) ?: return@runCatching null
            if (e.optInt("position", 0) == 4 && !e.optBoolean("disable")) {
                val role = e.optInt("role", 0)
                if (role == 1 || role == 2) depthRoles.getOrPut(e.optInt("depth", 4)) { HashSet() } += role
            }
        }
        var bad = 0
        val list = sorted.reversed().map { k ->
            val e = ents.getJSONObject(k)
            val content = e.opt("content") as? String ?: return@runCatching null
            val keyArr = e.optJSONArray("key") ?: return@runCatching null
            val kw = (0 until keyArr.length()).map { keyArr.optString(it) }
            val constant = e.optBoolean("constant")
            val position = e.optInt("position", 0)
            val role = e.optInt("role", 0)
            val depth = e.optInt("depth", 4)
            // 单独导出不带全局扫描设置：继承时用酒馆默认 2
            val scan = if (e.has("scanDepth") && !e.isNull("scanDepth")) e.optInt("scanDepth", 2) else 2
            val unsupported = position !in listOf(0, 1, 4) ||
                (position == 4 && (role !in listOf(1, 2) || depth < 0 || depth > 200 || (role == 2 && depth == 0) || (depthRoles[depth]?.size ?: 0) > 1)) ||
                (!constant && (kw.any(::badKeyword) || scan < 1 || scan > 200)) ||
                badRules(e, content, constant)
            if (unsupported) bad++
            val comment = e.optString("comment")
            WbEntry(
                id = e.opt("uid")?.toString()?.takeIf { it.isNotBlank() } ?: k,
                name = comment.ifEmpty { kw.firstOrNull().orEmpty() },
                enabled = !e.optBoolean("disable") && !unsupported,
                // 酒馆 order 大的插得靠后；这边优先级高的先放
                priority = -e.optInt("order", 100),
                position = when (position) { 0 -> WbPosition.BEFORE_SYSTEM; 4 -> WbPosition.DEPTH; else -> WbPosition.AFTER_SYSTEM },
                content = content,
                injectDepth = depth.coerceIn(0, 200),
                role = if (role == 2) "assistant" else "user",
                keywords = kw,
                caseSensitive = e.optBoolean("caseSensitive"),
                scanDepth = scan.coerceIn(1, 200),
                constantActive = constant,
                sticky = e.optInt("sticky").coerceIn(0, 10000),
                cooldown = e.optInt("cooldown").coerceIn(0, 10000),
                delay = e.optInt("delay").coerceIn(0, 10000),
            )
        }
        val name = o.optString("name").trim().ifEmpty { fileName.replace('\\', '/').substringAfterLast('/').replace(Regex("\\.json$", RegexOption.IGNORE_CASE), "") }
        Imported(fresh(WorldBook("", name, o.optString("description"), true, list)), bad)
    }.getOrNull()

    /** 书 id 和已有的重了、或者空，换新；条目 id 重的也换。 */
    private fun fresh(b: WorldBook): WorldBook {
        val id = b.id.trim().takeIf { it.isNotEmpty() && books.none { x -> x.id == it } } ?: WbEntry.newId()
        val seen = HashSet<String>()
        return b.copy(id = id, entries = b.entries.map { e -> val eid = e.id.trim().takeIf { it.isNotEmpty() && seen.add(it) } ?: WbEntry.newId().also { seen += it }; e.copy(id = eid) })
    }

    private val macro = Regex("\\{\\{[\\s\\S]*?\\}\\}|<(?:user|bot|char|charifnotgroup|group)>", RegexOption.IGNORE_CASE)

    private fun badRules(e: JSONObject, content: String, constant: Boolean): Boolean {
        if (content.startsWith("@@") || macro.containsMatchIn(content)) return true
        // 酒馆的时效在不同的边界过期，照抄数字会改变触发
        if (e.optInt("sticky") != 0 || e.optInt("cooldown") != 0 || e.optInt("delay") > 10000) return true
        if (!constant && e.optBoolean("selective", true) && (e.optJSONArray("keysecondary")?.length() ?: 0) > 0) return true
        if (e.optBoolean("useProbability", true) && e.optDouble("probability", 100.0) != 100.0) return true
        if (e.optString("group").isNotBlank() || e.optString("automationId").isNotBlank() || (e.optJSONArray("triggers")?.length() ?: 0) > 0) return true
        when (val r = e.opt("delayUntilRecursion")) { true -> return true; is Number -> if (r.toDouble() > 0) return true }
        e.optJSONObject("characterFilter")?.let { f -> if ((f.optJSONArray("names")?.length() ?: 0) > 0 || (f.optJSONArray("tags")?.length() ?: 0) > 0) return true }
        return !constant && listOf(
            "vectorized", "matchWholeWords", "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality",
            "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes",
        ).any { e.optBoolean(it) }
    }

    /** 宏、换行、或者合法的 /正则/ 写法（酒馆按最新消息往前扫、带分隔符，就算这边也能跑结果也可能不同，留给人看）。 */
    private fun badKeyword(raw: String): Boolean {
        if (macro.containsMatchIn(raw) || Regex("[\\r\\n\\u0001]").containsMatchIn(raw)) return true
        val m = Regex("^/([\\s\\S]+?)/([gimsuy]*)$").find(raw.trim()) ?: return false
        val pattern = m.groupValues[1]; val flags = m.groupValues[2]
        if (Regex("(^|[^\\\\])/").containsMatchIn(pattern) || flags.toSet().size != flags.length) return false
        return runCatching { Regex(pattern) }.isSuccess
    }
}
