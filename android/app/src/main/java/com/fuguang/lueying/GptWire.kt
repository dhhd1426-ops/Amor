package com.fuguang.lueying

import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT 账号这条通道只收 Responses 接口（POST /v1/responses），聊天那一整套（工具来回、思考、用量）写的是 Chat Completions。
 * 这里只做两头的翻译，中间那套一行不动：
 * - [request]：发出去之前，把 Chat Completions 的请求体换成 Responses 的；
 * - [Events]：收回来的每个事件，换成 Chat Completions 的一小块（choices[0].delta），原来的解析照旧认。
 *
 * 写法出处（2026-11-22 核对，见项目文档《GPT账号接入-官方核对》）：
 * - store:false、stream:true，每次带全历史；system 不能当消息发，放 instructions；
 * - 函数工具要包在 namespace 里：{type:"namespace", name, description, tools:[{type:"function", …}]}；
 * - 不收 temperature / top_p / max_output_tokens / metadata / truncation / user 等，这里只挑认得的字段往外写，别的一律不带。
 */
internal object GptWire {
    /** 工具分组的名字；模型回的调用里 namespace 就是它。 */
    const val NS = "amor"

    fun request(chat: JSONObject): JSONObject {
        val out = JSONObject().put("model", chat.optString("model"))
        val msgs = chat.optJSONArray("messages") ?: JSONArray()
        val sys = StringBuilder()
        val input = JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system", "developer" -> text(m.opt("content")).takeIf { it.isNotBlank() }?.let { if (sys.isNotEmpty()) sys.append("\n\n"); sys.append(it) }
                "user" -> input.put(JSONObject().put("role", "user").put("content", userParts(m.opt("content"))))
                "assistant" -> {
                    text(m.opt("content")).takeIf { it.isNotBlank() }?.let {
                        input.put(JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", it))))
                    }
                    m.optJSONArray("tool_calls")?.let { tc ->
                        for (k in 0 until tc.length()) {
                            val c = tc.optJSONObject(k) ?: continue
                            val f = c.optJSONObject("function") ?: continue
                            input.put(
                                JSONObject().put("type", "function_call").put("call_id", c.optString("id")).put("namespace", NS)
                                    .put("name", f.optString("name")).put("arguments", f.optString("arguments").ifBlank { "{}" }),
                            )
                        }
                    }
                }
                "tool" -> input.put(JSONObject().put("type", "function_call_output").put("call_id", m.optString("tool_call_id")).put("output", text(m.opt("content"))))
            }
        }
        if (sys.isNotEmpty()) out.put("instructions", sys.toString())
        out.put("input", input)
        chat.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { arr ->
            val fs = JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i)?.optJSONObject("function") ?: continue
                fs.put(
                    JSONObject().put("type", "function").put("name", f.optString("name")).put("description", f.optString("description"))
                        .put("parameters", f.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject())).put("strict", false),
                )
            }
            if (fs.length() > 0) out.put("tools", JSONArray().put(JSONObject().put("type", "namespace").put("name", NS).put("description", "Amor 里她能用的工具").put("tools", fs)))
        }
        // 思考档：Chat Completions 写 reasoning_effort，这边写 reasoning.effort；要一份思考摘要才看得见她在想什么
        chat.optString("reasoning_effort").takeIf { it.isNotBlank() }?.let { out.put("reasoning", JSONObject().put("effort", it).put("summary", "auto")) }
        return out.put("store", false).put("stream", true)
    }

    /** 一次不带工具的问答（测试模型、整理记忆、看图）。content：一句话，或 Chat Completions 的内容块数组。 */
    fun ask(model: String, system: String, content: Any): JSONObject {
        val out = JSONObject().put("model", model)
        if (system.isNotBlank()) out.put("instructions", system)
        return out.put("input", JSONArray().put(JSONObject().put("role", "user").put("content", userParts(content)))).put("store", false).put("stream", true)
    }

    private fun text(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    private fun userParts(c: Any?): JSONArray {
        val out = JSONArray()
        when (c) {
            is String -> out.put(JSONObject().put("type", "input_text").put("text", c))
            is JSONArray -> for (i in 0 until c.length()) {
                val p = c.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> out.put(JSONObject().put("type", "input_text").put("text", p.optString("text")))
                    "image_url" -> {
                        val u = p.optJSONObject("image_url")?.optString("url") ?: p.optString("image_url")
                        if (u.isNotBlank()) out.put(JSONObject().put("type", "input_image").put("image_url", u))
                    }
                }
            }
        }
        if (out.length() == 0) out.put(JSONObject().put("type", "input_text").put("text", ""))
        return out
    }

    /** 流里出的错：code 是服务器给的原样（subscription_sharing_usage_limit_exceeded 之类），没给就是空的。 */
    class Failed(val code: String, val detail: String) : Exception(code.ifBlank { detail })

    /** 一次回复的事件流。每来一个事件调 [map]；[done] 为真就是收完了（这条通道没有 [DONE] 那一行）。 */
    class Events {
        var done = false
            private set
        private val slot = HashMap<Int, Int>()
        private val fed = HashSet<Int>()
        private var parts = 0

        private fun chunk(delta: JSONObject, finish: String? = null) =
            JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)))

        private fun call(i: Int, id: String?, name: String?, args: String?): JSONObject {
            val f = JSONObject()
            if (name != null) f.put("name", name)
            if (args != null) f.put("arguments", args)
            val c = JSONObject().put("index", i).put("function", f)
            if (id != null) c.put("id", id)
            return chunk(JSONObject().put("tool_calls", JSONArray().put(c)))
        }

        private fun bare(name: String) = name.removePrefix("$NS.").removePrefix("${NS}__")

        fun map(e: JSONObject): JSONObject? {
            when (e.optString("type")) {
                "response.output_text.delta" -> return e.optString("delta").takeIf { it.isNotEmpty() }?.let { chunk(JSONObject().put("content", it)) }
                "response.reasoning_summary_part.added" -> return if (parts++ > 0) chunk(JSONObject().put("reasoning_content", "\n\n")) else null
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    return e.optString("delta").takeIf { it.isNotEmpty() }?.let { chunk(JSONObject().put("reasoning_content", it)) }
                "response.output_item.added" -> {
                    val item = e.optJSONObject("item") ?: return null
                    if (item.optString("type") != "function_call") return null
                    val oi = e.optInt("output_index", slot.size)
                    val i = slot.getOrPut(oi) { slot.size }
                    return call(i, item.optString("call_id").ifBlank { item.optString("id") }, bare(item.optString("name")), "")
                }
                "response.function_call_arguments.delta" -> {
                    val i = slot[e.optInt("output_index", -1)] ?: return null
                    val d = e.optString("delta"); if (d.isEmpty()) return null
                    fed += i
                    return call(i, null, null, d)
                }
                "response.output_item.done" -> {
                    val item = e.optJSONObject("item") ?: return null
                    if (item.optString("type") != "function_call") return null
                    val oi = e.optInt("output_index", slot.size)
                    val known = slot[oi]
                    val args = item.optString("arguments")
                    // 有的时候参数不分段来，只在收尾这一下整份给
                    if (known == null) { val i = slot.size; slot[oi] = i; fed += i; return call(i, item.optString("call_id").ifBlank { item.optString("id") }, bare(item.optString("name")), args) }
                    if (known !in fed && args.isNotEmpty()) { fed += known; return call(known, null, null, args) }
                    return null
                }
                "response.completed" -> {
                    done = true
                    val u = e.optJSONObject("response")?.optJSONObject("usage")
                    val out = chunk(JSONObject(), if (slot.isEmpty()) "stop" else "tool_calls")
                    if (u != null) out.put(
                        "usage",
                        JSONObject().put("prompt_tokens", u.optLong("input_tokens")).put("completion_tokens", u.optLong("output_tokens"))
                            .put("prompt_tokens_details", JSONObject().put("cached_tokens", u.optJSONObject("input_tokens_details")?.optLong("cached_tokens") ?: 0L)),
                    )
                    return out
                }
                "response.incomplete" -> {
                    done = true
                    val why = e.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                    if (why == "max_output_tokens") return chunk(JSONObject(), "length")
                    throw Failed("response_incomplete", why)
                }
                "response.failed" -> {
                    val err = e.optJSONObject("response")?.optJSONObject("error")
                    throw Failed(err?.optString("code").orEmpty(), err?.optString("message").orEmpty().take(200))
                }
                "error" -> {
                    val err = e.optJSONObject("error") ?: e
                    throw Failed(err.optString("code"), err.optString("message").take(200))
                }
            }
            return null
        }
    }
}
