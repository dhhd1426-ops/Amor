package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * 拆书页：assets/chaishu/index.html，从右边推进来盖住整个 App（进 decel 400ms，出 accel 300ms）。
 * 页面通过 window.CS 调这边：存取书、调用分析模型、选文件。
 * 系统返回键先交给页面（关弹层、回上一层），页面说到底了才关掉。
 */
@Composable
fun ChaishuLayer(store: AssistantStore) {
    val open = Chaishu.open
    var shown by remember { mutableStateOf(false) }
    if (open) shown = true
    val t by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 400 else 300, easing = if (open) Curves.decel else Curves.accel), label = "chaishu") { if (it == 0f) shown = false }
    if (!shown) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density) / density.density
    val bottom = WindowInsets.navigationBars.getBottom(density) / density.density
    val web = remember { mutableStateOf<WebView?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val w = web.value ?: return@rememberLauncherForActivityResult
        if (uri == null) { w.evaluateJavascript("window.cs&&cs.picked(null)", null); return@rememberLauncherForActivityResult }
        scope.launch {
            val out = withContext(Dispatchers.IO) { ChaishuFiles.import(ctx, uri) }
            w.evaluateJavascript("window.cs&&cs.picked(${out})", null)
        }
    }

    BackHandler(enabled = open) {
        val w = web.value
        if (w == null) Chaishu.open = false
        else w.evaluateJavascript("window.cs?cs.back():false") { r -> if (r != "true") Chaishu.open = false }
    }
    DisposableEffect(Unit) { onDispose { web.value?.destroy(); web.value = null } }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { translationX = size.width * (1f - t) }
            .background(Skin.page),
    ) {
        AndroidView(
            factory = { c -> chaishuWeb(c, scope, store, top, bottom, { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "text/*")) }).also { web.value = it } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val HOST = "chaishu.app"

private data class ChaishuModel(val provider: Provider, val config: ProviderConfig, val name: String)


@SuppressLint("SetJavaScriptEnabled")
private fun chaishuWeb(ctx: Context, scope: CoroutineScope, store: AssistantStore, top: Float, bottom: Float, onPick: () -> Unit): WebView {
    val main = Handler(Looper.getMainLooper())
    lateinit var w: WebView
    fun js(code: String) = main.post { runCatching { w.evaluateJavascript(code, null) } }

    class Bridge {
        @JavascriptInterface fun list(): String = Chaishu.list()
        @JavascriptInterface fun save(id: String, json: String): Boolean = runCatching { Chaishu.save(id, json) }.getOrDefault(false)
        @JavascriptInterface fun remove(id: String) { main.post { Chaishu.remove(id) } }
        @JavascriptInterface fun close() { main.post { Chaishu.open = false } }
        @JavascriptInterface fun pick() { main.post { onPick() } }
        private val sessions = ConcurrentHashMap<String, ChaishuModel>()

        @JavascriptInterface fun models(): String {
            val active = JSONObject().put("provider", store.activeProvider)
                .put("name", store.activeModel).put("label", store.config(store.activeProvider).name)
            val options = JSONArray()
            for (provider in store.ordered()) {
                val config = store.config(provider.id)
                if (!config.configured) continue
                val names = if (store.activeProvider == provider.id) config.models + store.activeModel else config.models
                for (name in names.filter { it.isNotBlank() && ModelSort.chat(it) }.distinct()) {
                    options.put(JSONObject().put("provider", provider.id).put("name", name).put("label", config.name))
                }
            }
            return JSONObject().put("active", active).put("options", options).toString()
        }

        @JavascriptInterface fun begin(providerId: String, modelName: String): String {
            val following = providerId.isEmpty() && modelName.isEmpty()
            val id = if (following) store.activeProvider else providerId
            val name = if (following) store.activeModel else modelName
            val provider = PROVIDERS.firstOrNull { it.id == id }
            if (provider == null) return JSONObject().put("error", "找不到当前模型的供应商").toString()
            val config = store.config(id)
            if (!config.configured || name.isBlank()) {
                return JSONObject().put("error", "请先配置 ${config.name} 的模型和 API Key").toString()
            }
            if (!following && name !in config.models && !(id == store.activeProvider && name == store.activeModel)) {
                return JSONObject().put("error", "这个分析模型已从配置中移除").toString()
            }
            val session = UUID.randomUUID().toString()
            sessions[session] = ChaishuModel(provider, config, name)
            return JSONObject().put("session", session).put("name", name).put("provider", id).put("label", config.name).toString()
        }

        @JavascriptInterface fun end(session: String) { sessions.remove(session) }

        @JavascriptInterface fun ask(session: String, req: String, system: String, user: String, maxTokens: Int) {
            val model = sessions[session]
            if (model == null) {
                js("window.cs&&cs.reply(${JSONObject.quote(req)},false,${JSONObject.quote("分析任务已结束")},true)")
                return
            }
            scope.launch {
                try {
                    val text = ChatApi.complete(model.provider, model.config, model.name, system, user, maxTokens.coerceIn(512, 8192))
                    js("window.cs&&cs.reply(${JSONObject.quote(req)},true,${JSONObject.quote(text)},false)")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val text = MemModel.why(error, model.config.name, model.name)
                    js("window.cs&&cs.reply(${JSONObject.quote(req)},false,${JSONObject.quote(text)},${MemModel.fatal(text)})")
                }
            }
        }
    }

    fun font(name: String): Int = when (name) { "500" -> R.font.dm_sans_medium; "600" -> R.font.dm_sans_semibold; else -> R.font.dm_sans_regular }

    fun serve(u: Uri): WebResourceResponse? {
        if (u.host != HOST) return null
        val p = u.path.orEmpty()
        val none = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        return runCatching {
            when {
                p == "/" || p == "/index.html" -> WebResourceResponse("text/html", "utf-8", ctx.assets.open("chaishu/index.html"))
                p.startsWith("/font/") -> WebResourceResponse("font/ttf", null, ctx.resources.openRawResource(font(p.removePrefix("/font/").removeSuffix(".ttf"))))
                p.startsWith("/text/") -> Chaishu.textFile(p.removePrefix("/text/"))?.takeIf { it.isFile }?.let { WebResourceResponse("text/plain", "utf-8", it.inputStream()) } ?: none
                else -> none
            }
        }.getOrDefault(none)
    }

    w = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Skin.page.toArgb())
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.textZoom = 100
        addJavascriptInterface(Bridge(), "CS")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = serve(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        val skin = if (Skin.light) "light" else "dark"
        loadUrl("https://$HOST/index.html?skin=$skin&top=${top.toInt()}&bottom=${bottom.toInt()}")
    }
    return w
}

/** 选来的文件：读成文字存进 filesDir/chaishu/<id>.txt，返回给页面 {ok,id,name} 或 {ok:false,why}。 */
object ChaishuFiles {
    private const val MAX = 12 * 1024 * 1024

    fun import(ctx: Context, uri: Uri): String = runCatching {
        val name = displayName(ctx, uri).ifBlank { "未命名" }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { s -> s.readNBytesCompat(MAX + 1) } ?: return fail("读不到这个文件")
        if (bytes.size > MAX) return fail("文件太大了（超过 12 MB）")
        val text = if (name.lowercase().endsWith(".epub") || isZip(bytes)) epub(bytes) else decode(bytes)
        if (text.isBlank()) return fail("这个文件里读不出文字")
        val id = "b" + System.currentTimeMillis().toString(36)
        val f = Chaishu.textFile(id) ?: return fail("存不下")
        f.writeText(text.replace("\r\n", "\n").replace('\r', '\n'))
        JSONObject().put("ok", true).put("id", id).put("name", name.substringBeforeLast('.')).toString()
    }.getOrElse { fail("读文件出错：" + (it.message ?: it.javaClass.simpleName).take(60)) }

    private fun fail(why: String) = JSONObject().put("ok", false).put("why", why).toString()

    private fun displayName(ctx: Context, uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** 先按 UTF-8 严格解，解不通就是 GB18030（GBK 的超集）。 */
    fun decode(b: ByteArray): String {
        val start = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        val buf = ByteBuffer.wrap(b, start, b.size - start)
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString()
        }.getOrElse { String(b, start, b.size - start, charset("GB18030")) }
    }

    /** epub：按文件名顺序取 xhtml，标题和段落各占一行。 */
    private fun epub(b: ByteArray): String {
        val parts = sortedMapOf<String, String>(compareBy<String> { it.length }.thenBy { it })
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                if (!e.isDirectory && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                    val doc = org.jsoup.Jsoup.parse(String(z.readBytes(), Charsets.UTF_8))
                    parts[e.name] = doc.select("h1,h2,h3,h4,p").joinToString("\n") { it.text() }
                }
            }
        }
        return parts.values.joinToString("\n\n")
    }
}
