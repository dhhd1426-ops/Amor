package com.fuguang.lueying

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * 用 ChatGPT 账号的套餐额度聊天（OpenAI 官方的 Sign in with ChatGPT，开源 / 本机应用那条自助注册的路）。
 * 「我的 › 功能 › GPT 控制台」：启用 → 拉起浏览器登录 → 列出能用的模型 → 点一个先真发一句，通了全局模型就换成它。
 *
 * 照官方文档和官方开发包（openai/sign-in-with-chatgpt-devkit 的 packages/local/src/oauth.ts）的做法写，没有搬它的代码。
 * 核对过的规矩都在项目文档《GPT账号接入-官方核对》里，改之前先读：
 * - 回调只能是 http://127.0.0.1:端口/auth/callback，所以登录在系统浏览器里做，Amor 在本机开一个只收这一次回调的端口；
 * - 第一次用 client_id=dynamic_agent_client 注册，回调带回签发的 client_id，存下来以后都用它；
 * - 访问令牌 1 小时，刷新令牌 30 天且每次刷新都换新的：刷新要排队（[lock]），换下来的马上落盘（commit）；
 * - 令牌只放本机，不进诊断框、不进备份（Backup.prefSkipped 里有 [FILE]）；换手机要重新登录，两台手机拿同一把刷新令牌会互相顶掉；
 * - 官方没给应用读剩余额度的任何办法，条款也不许碰 ChatGPT 的密码和 cookie，所以这里只给一个去官方用量页的入口。
 */
object Gpt {
    const val ID = "chatgpt"
    const val BASE = "https://api.openai.com/v1"
    const val USAGE_URL = "https://chatgpt.com/settings/usage"
    const val FILE = "gpt-account"
    private const val ISSUER = "https://auth.openai.com"
    private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val CALLBACK = "/auth/callback"
    private const val PORT = 1455
    private val REAUTH = setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")

    class Model(val slug: String, val name: String)

    /** 开关。关着：令牌留着，但模型不出现在选择面板里，聊天也不走它。 */
    var on by mutableStateOf(false)
        private set
    var signedIn by mutableStateOf(false)
        private set
    var who by mutableStateOf("")
        private set
    var mail by mutableStateOf("")
        private set
    val models = mutableStateListOf<Model>()
    /** ""：没在登录；"wait"：等浏览器回来；"check"：换令牌、读模型。 */
    var phase by mutableStateOf("")
        private set
    var loginDiag by mutableStateOf("")
        private set
    /** 正在测试的模型（slug）；空 = 没在测。 */
    var testing by mutableStateOf("")
        private set
    var testDiag by mutableStateOf("")
        private set

    /** 界面上那一份助理设置；登录、退出、开关变了就把「ChatGPT」这一家写进去或拿掉。 */
    @Volatile var main: AssistantStore? = null

    private lateinit var app: Context
    private var sp: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loginJob: Job? = null

    @Volatile private var hostId = ""
    @Volatile private var clientId = ""
    @Volatile private var sub = ""
    @Volatile private var access = ""
    @Volatile private var refresh = ""
    @Volatile private var expiresAt = 0L
    @Volatile private var scopes = ""
    /** 换成 ChatGPT 之前用的「供应商|型号」：关掉或退出时退回去。 */
    @Volatile private var prev = ""
    @Volatile private var lastHeaders = ""
    @Volatile private var lastChat = ""
    private var ver = "?"

    @Synchronized
    fun init(ctx: Context) {
        if (sp != null) return
        app = ctx.applicationContext
        ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val s = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = s
        runCatching {
            val o = JSONObject(s.getString("state", null) ?: "{}")
            hostId = o.optString("host"); clientId = o.optString("client"); sub = o.optString("sub")
            access = o.optString("access"); refresh = o.optString("refresh"); expiresAt = o.optLong("exp"); scopes = o.optString("scopes"); prev = o.optString("prev")
            on = o.optBoolean("on"); who = o.optString("name"); mail = o.optString("email")
            signedIn = refresh.isNotEmpty() && clientId.isNotEmpty()
            models.clear()
            o.optJSONArray("models")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { m -> models += Model(m.optString("s"), m.optString("n")) } }
        }
        if (hostId.isEmpty()) { hostId = "urn:uuid:" + UUID.randomUUID(); save() }
    }

    @Synchronized
    private fun save() {
        val o = JSONObject().put("host", hostId).put("client", clientId).put("sub", sub).put("access", access).put("refresh", refresh).put("exp", expiresAt)
            .put("scopes", scopes).put("prev", prev).put("on", on).put("name", who).put("email", mail)
            .put("models", JSONArray(models.toList().map { JSONObject().put("s", it.slug).put("n", it.name) }))
        // commit：刷新令牌换下来的那一把已经作废，新的没落盘就丢了登录
        sp?.edit()?.putString("state", o.toString())?.commit()
    }

    /** 把「ChatGPT」这一家按现在的状态写进助理设置：登录了又开着才算填好了（才出现在选择面板里）。 */
    fun fill(store: AssistantStore) {
        val live = on && signedIn
        store.configs[ID] = ProviderConfig(
            name = "ChatGPT", key = if (live) "siwc" else "", base = BASE, path = "/responses", enabled = live,
            models = if (live) models.map { it.slug } else emptyList(),
        )
        if (!live && store.activeProvider == ID) {
            val (pid, m) = prev.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to "" }
            val back = PROVIDERS.firstOrNull { it.id == pid && it.id != ID && store.config(it.id).configured }
            val next = back ?: store.ready().firstOrNull { it.id != ID } ?: BUILTIN_PROVIDERS.first()
            store.use(next.id, if (back != null) m else store.config(next.id).models.firstOrNull().orEmpty())
        }
    }

    private suspend fun sync() = withContext(Dispatchers.Main) { main?.let { fill(it) } }

    fun toggle(v: Boolean) {
        on = v; save()
        main?.let { fill(it) }
        if (!v) cancelLogin()
    }

    /** 现在聊天走的是不是它的哪个模型（没走就是空的）。 */
    fun using(store: AssistantStore): String = if (store.activeProvider == ID) store.activeModel else ""

    fun title(slug: String): String = models.firstOrNull { it.slug == slug }?.name?.ifBlank { slug } ?: slug

    // ───────── 令牌 ─────────

    /** 发请求前要一把能用的访问令牌：快过期（两分钟内）就先刷新。 */
    suspend fun token(): String = lock.withLock {
        if (!signedIn || refresh.isEmpty()) throw IllegalStateException("ChatGPT 账号还没登录（我的 › 功能 › GPT 控制台）")
        if (access.isNotEmpty() && System.currentTimeMillis() < expiresAt - 120_000) return@withLock access
        renew()
        access
    }

    private suspend fun renew() = withContext(Dispatchers.IO) {
        val conf = discovery()
        val r = try {
            http(conf.token, form = mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh, "resource" to BASE))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连不上 ChatGPT 的登录服务，检查网络")
        }
        if (r.code !in 200..299) {
            val (code, _) = errorOf(r.body)
            if (code in REAUTH) {
                access = ""; refresh = ""; expiresAt = 0; save()
                withContext(Dispatchers.Main) { signedIn = false; loginDiag = diag("续登录", listOf("HTTP ${r.code}  $code", "request-id  ${r.requestId}")) }
                sync()
                throw IllegalStateException("ChatGPT 登录过期了，到 GPT 控制台重新登录一次")
            }
            throw IllegalStateException(human(r.code, code) + "（续登录）")
        }
        val o = JSONObject(r.body)
        val a = o.optString("access_token")
        if (a.isEmpty()) throw IllegalStateException("ChatGPT 没回新的令牌（续登录）")
        access = a
        o.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { refresh = it }
        expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        o.optString("scope").takeIf { it.isNotBlank() }?.let { scopes = it }
        save()
    }

    // ───────── 登录 ─────────

    fun login() {
        if (phase.isNotEmpty()) return
        phase = "wait"; loginDiag = ""
        loginJob = scope.launch { runLogin() }
    }

    fun cancelLogin() { loginJob?.cancel() }

    private suspend fun runLogin() {
        var step = "读登录配置"
        var server: ServerSocket? = null
        try {
            val conf = discovery()
            step = "开本机回调端口"
            server = listen()
            val redirect = "http://127.0.0.1:${server.localPort}$CALLBACK"
            val state = rand(); val nonce = rand(); val verifier = rand()
            val known = clientId
            val q = linkedMapOf(
                "client_id" to known.ifEmpty { "dynamic_agent_client" }, "response_type" to "code", "redirect_uri" to redirect, "scope" to SCOPES, "resource" to BASE,
                "state" to state, "nonce" to nonce, "code_challenge_method" to "S256", "code_challenge" to b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
                "ext_agent_host_id" to hostId,
            )
            if (known.isEmpty()) q["agent_name_hint"] = "Amor" else if (mail.isNotEmpty()) q["login_hint"] = mail
            step = "拉起浏览器"
            val url = conf.authorize + "?" + q.entries.joinToString("&") { it.key + "=" + enc(it.value) }
            withContext(Dispatchers.Main) { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            step = "等浏览器回来（${server.localPort} 端口）"
            val cb = await(server, state)
            runCatching { server.close() }
            // 回到 Amor：后台拉起自己不一定被系统允许，回调页上另有一个「回到 Amor」的链接
            runCatching { withContext(Dispatchers.Main) { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } }
            withContext(Dispatchers.Main) { phase = "check" }
            cb["error"]?.let { throw Stop("授权页回了 error=$it" + (cb["error_description"]?.let { d -> "\n" + d.take(200) } ?: "")) }
            val code = cb["code"] ?: throw Stop("回调里没有 code")
            val cid = cb["client_id"] ?: known
            if (cid.isEmpty() || cid == "dynamic_agent_client" || !Regex("^[a-zA-Z0-9_-]{1,200}$").matches(cid) || (known.isNotEmpty() && cb["client_id"] != null && cb["client_id"] != known))
                throw Stop("回调里的 client_id 不对：注册没完成")
            // 先存注册：code 只能用一次，换令牌失败了下次也不用再注册一个
            clientId = cid; save()
            step = "换令牌"
            val r = http(conf.token, form = mapOf("grant_type" to "authorization_code", "client_id" to cid, "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to BASE))
            if (r.code !in 200..299) { val (c, m) = errorOf(r.body); throw Stop("HTTP ${r.code}  $c\n${m.take(200)}\nrequest-id  ${r.requestId}") }
            val o = JSONObject(r.body)
            val granted = o.optString("scope")
            val a = o.optString("access_token"); val rf = o.optString("refresh_token"); val idt = o.optString("id_token")
            if (a.isEmpty() || rf.isEmpty() || idt.isEmpty()) throw Stop("令牌没回全：access ${a.isNotEmpty()} · refresh ${rf.isNotEmpty()} · id ${idt.isNotEmpty()}\nscope  $granted")
            step = "核对身份"
            val claims = verify(idt, cid, nonce, conf.jwks)
            val newSub = claims.optString("sub")
            if (sub.isNotEmpty() && known.isNotEmpty() && newSub != sub) {
                // 注册是跟着账号走的：这次登的是另一个账号，旧注册不能给它用。清掉，下次按新账号重新注册
                clientId = ""; sub = ""; save()
                throw Stop("这次登录的是另一个 ChatGPT 账号。再点一次登录，按新账号重新注册")
            }
            if (PLAN_SCOPE !in granted.split(Regex("\\s+"))) throw Stop("登录成功，但没拿到用套餐额度的权限\nscope  $granted\n（套餐额度只对 Plus、Pro 开放；授权页上那一项也要同意）")
            sub = newSub; access = a; refresh = rf; scopes = granted
            expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
            val name = claims.optString("name"); val email = claims.optString("email")
            save()
            step = "读模型列表"
            val list = fetchModels(a)
            withContext(Dispatchers.Main) {
                who = name; mail = email
                models.clear(); models.addAll(list)
                signedIn = true
            }
            save()
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val lines = mutableListOf("走到：$step")
            lines += when (e) {
                is Stop -> e.message.orEmpty()
                is java.net.UnknownHostException -> "连不上：${e.message}（网络或代理）"
                is java.io.IOException -> "网络出错：${e.javaClass.simpleName} ${e.message.orEmpty().take(160)}"
                else -> "${e.javaClass.simpleName}：${e.message.orEmpty().take(300)}"
            }
            val text = diag("登录", lines)
            withContext(Dispatchers.Main) { loginDiag = text }
        } finally {
            runCatching { server?.close() }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { phase = "" }
        }
    }

    private class Stop(message: String) : Exception(message)

    private fun listen(): ServerSocket {
        val lo = InetAddress.getByName("127.0.0.1")
        return runCatching { ServerSocket(PORT, 4, lo) }.getOrElse { ServerSocket(0, 4, lo) }.apply { soTimeout = 1000 }
    }

    /** 等浏览器跳回来的那一次请求；别的请求（图标之类）回 404 接着等。最多等 10 分钟。 */
    private suspend fun await(server: ServerSocket, state: String): Map<String, String> {
        val until = System.currentTimeMillis() + 600_000
        while (System.currentTimeMillis() < until) {
            coroutineContext.ensureActive()
            val s = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            try {
                s.soTimeout = 5000
                val rd = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val first = rd.readLine().orEmpty()
                while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break }
                val target = first.split(" ").getOrNull(1).orEmpty()
                val q = target.substringAfter('?', "").split("&").filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val ok = first.startsWith("GET ") && target.substringBefore('?') == CALLBACK && q["state"] == state
                val body = if (ok) PAGE else "Not found"
                val bytes = body.toByteArray(Charsets.UTF_8)
                s.getOutputStream().apply {
                    write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: ${if (ok) "text/html" else "text/plain"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(bytes); flush()
                }
                if (ok) return q
            } catch (_: java.io.IOException) {
            } finally { runCatching { s.close() } }
        }
        throw Stop("等了 10 分钟浏览器没跳回来")
    }

    private val PAGE get() = "<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>回到 Amor</title>" +
        "<style>body{font:17px system-ui;max-width:30rem;margin:18vh auto;padding:24px;color:#202123}a{display:inline-block;margin-top:18px;padding:12px 22px;border-radius:24px;background:#202123;color:#fff;text-decoration:none}</style>" +
        "<h1 style=\"font-size:24px\">登录好了</h1><p>Amor 正在核对这次登录，回去就能看到。</p>" +
        "<a href=\"intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${app.packageName};end\">回到 Amor</a></html>"

    /** 核对 ID 令牌：签名（官方公钥，RS256）、签发方、给谁的、过没过期、nonce。 */
    private fun verify(idToken: String, cid: String, nonce: String, jwksUrl: String): JSONObject {
        val parts = idToken.split(".")
        if (parts.size != 3) throw Stop("ID 令牌格式不对")
        val head = JSONObject(String(unb64(parts[0]), Charsets.UTF_8))
        val claims = JSONObject(String(unb64(parts[1]), Charsets.UTF_8))
        if (head.optString("alg") != "RS256") throw Stop("ID 令牌的签名算法不是 RS256：${head.optString("alg")}")
        val r = http(jwksUrl)
        if (r.code != 200) throw Stop("拿不到核对身份用的公钥（HTTP ${r.code}）")
        val keys = JSONObject(r.body).optJSONArray("keys") ?: JSONArray()
        val kid = head.optString("kid")
        val key = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }.firstOrNull { it.optString("kty") == "RSA" && (kid.isEmpty() || it.optString("kid") == kid) }
            ?: throw Stop("公钥里没有这把（kid $kid）")
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, unb64(key.optString("n"))), BigInteger(1, unb64(key.optString("e")))))
        val good = Signature.getInstance("SHA256withRSA").run { initVerify(pub); update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)); verify(unb64(parts[2])) }
        if (!good) throw Stop("ID 令牌签名对不上")
        val aud = claims.opt("aud")
        val audOk = when (aud) { is String -> aud == cid; is JSONArray -> (0 until aud.length()).any { aud.optString(it) == cid }; else -> false }
        val now = System.currentTimeMillis() / 1000
        if (claims.optString("iss") != ISSUER || !audOk || claims.optLong("exp") < now - 5 || claims.optString("sub").isEmpty() || claims.optString("nonce") != nonce ||
            (claims.has("azp") && claims.optString("azp") != cid)) throw Stop("ID 令牌内容对不上（iss / aud / exp / nonce）")
        return claims
    }

    fun signOut() {
        val token = refresh; val cid = clientId
        access = ""; refresh = ""; expiresAt = 0; save()
        signedIn = false; loginDiag = ""; testDiag = ""
        main?.let { fill(it) }
        // 吊销刷新令牌（尽力而为）；注册和这台手机的 host id 留着，下次登录还用
        if (token.isNotEmpty()) scope.launch {
            val ok = runCatching { http(discovery().revoke, form = mapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to cid)).code == 200 }.getOrDefault(false)
            if (!ok) withContext(Dispatchers.Main) { loginDiag = diag("退出登录", listOf("本机的登录已经清掉，但没能确认 ChatGPT 那边也断开了。", "到 ChatGPT 设置 › 安全与登录 › Sign in with ChatGPT 里把 Amor 断开")) }
        }
    }

    // ───────── 模型 ─────────

    private fun fetchModels(bearer: String): List<Model> {
        val r = http("$BASE/models", bearer = bearer)
        if (r.code !in 200..299) { val (c, m) = errorOf(r.body); throw Stop("HTTP ${r.code}  $c\n${m.take(200)}\nrequest-id  ${r.requestId}") }
        val arr = JSONObject(r.body).optJSONArray("models") ?: throw Stop("模型列表的格式不认识：" + r.body.take(160))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("visibility") == "list" && it.optString("slug").isNotBlank() }
            .map { Model(it.optString("slug"), it.optString("display_name").ifBlank { it.optString("slug") }) }
    }

    /** 进控制台时悄悄重读一遍（账号的套餐变了，能用的模型也会变）；读不到就留着上次的。 */
    fun refreshModels() {
        if (!signedIn || phase.isNotEmpty()) return
        scope.launch {
            val list = runCatching { fetchModels(token()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@launch
            withContext(Dispatchers.Main) { models.clear(); models.addAll(list) }
            save(); sync()
        }
    }

    /** 点一个模型：真发一句，等它回完才算通；通了全局模型换成它。不通，原来那个不动，下面出诊断框。 */
    fun test(slug: String) {
        if (testing.isNotEmpty()) return
        testing = slug; testDiag = ""
        scope.launch {
            val err = try { ask(slug, "", "hi"); null } catch (e: CancellationException) { throw e } catch (e: Throwable) { e }
            withContext(Dispatchers.Main) {
                if (err == null) main?.let { s ->
                    if (s.activeProvider != ID) { prev = s.activeProvider + "|" + s.activeModel }
                    fill(s); s.use(ID, slug)
                } else testDiag = diag("测试模型", listOf("model  $slug", err.message.orEmpty().take(400)) + lastChat.lines().filter { it.isNotBlank() })
                testing = ""
            }
            save()
        }
    }

    /** 一次不带工具的问答，返回正文（整理记忆、看图、给思考分步也走这里）。 */
    suspend fun ask(model: String, system: String, content: Any): String = withContext(Dispatchers.IO) {
        val bearer = token()
        val conn = URL("$BASE/responses").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 120_000; conn.doOutput = true; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(GptWire.ask(model, system, content).toString().toByteArray()) }
            val code = conn.responseCode
            seen(conn)
            if (code !in 200..299) throw IllegalStateException(chatError(code, conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(), conn, model))
            val ev = GptWire.Events()
            val text = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (!ev.done) {
                    ensureActive()
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val o = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    val c = (try { ev.map(o) } catch (f: GptWire.Failed) { throw IllegalStateException(streamError(f, model)) }) ?: continue
                    c.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")?.let { text.append(it) }
                }
            }
            if (!ev.done) throw IllegalStateException("ChatGPT 的回复没收完就断了")
            text.toString()
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("连不上 api.openai.com，检查网络或代理")
        } catch (e: java.io.IOException) {
            throw IllegalStateException("连接中断：" + (e.message ?: e.javaClass.simpleName).take(80))
        } finally { runCatching { conn.disconnect() } }
    }

    // ───────── 报错、诊断 ─────────

    /** 记下这次回包的头（不含 cookie）：官方没说带不带额度，真机上看一眼才知道。 */
    fun seen(conn: HttpURLConnection) {
        lastHeaders = runCatching {
            conn.headerFields.entries.filter { (k, _) -> k != null && !k.equals("set-cookie", true) && (k.startsWith("x-", true) || k.contains("ratelimit", true) || k.contains("openai", true) || k.equals("retry-after", true)) }
                .joinToString("\n") { (k, v) -> "${k.lowercase()}: ${v.joinToString(",").take(160)}" }
        }.getOrDefault("")
    }

    /** 聊天这条路被拒了：返回给聊天页看的一句话，细节记进诊断。 */
    fun chatError(code: Int, body: String, conn: HttpURLConnection, model: String): String {
        val (c, m) = errorOf(body)
        lastChat = "聊天 · $model\nHTTP $code  $c\n${m.take(300)}\nparam  ${paramOf(body)}\nrequest-id  ${conn.getHeaderField("x-request-id").orEmpty()}"
        return human(code, c)
    }

    internal fun streamError(f: GptWire.Failed, model: String): String {
        lastChat = "聊天 · $model（回到一半）\n${f.code}\n${f.detail}"
        return human(0, f.code)
    }

    private fun human(status: Int, code: String): String = when {
        code == "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 套餐额度用到头了，或者到了你给 Amor 设的上限。到 ChatGPT 设置 › 用量里看"
        code == "subscription_sharing_user_not_eligible" -> "这个 ChatGPT 账号用不了套餐额度（要 Plus 或 Pro）"
        code == "subscription_sharing_usage_unavailable" || code == "subscription_sharing_user_unavailable" -> "ChatGPT 那边暂时查不了额度，过一会儿再试"
        code == "subscription_sharing_unsupported_capability" -> "请求里带了这条通道不收的东西（细节在 GPT 控制台的诊断里）"
        code == "subscription_sharing_route_not_supported" -> "这条通道不收这个接口"
        code == "subscription_sharing_invalid_user" -> "ChatGPT 没认这次登录，到 GPT 控制台看一下"
        code.startsWith("chatpass_v2") -> "授权范围不够，退出登录再登录一次"
        code == "model_not_found" -> "这个账号用不了这个模型，换一个"
        code == "response_incomplete" -> "她没写完就停了，可以让她接着说"
        code in REAUTH -> "ChatGPT 登录过期了，到 GPT 控制台重新登录一次"
        status == 401 -> "ChatGPT 没认这次登录（401）"
        status == 403 -> "ChatGPT 拒绝了（403）：账号、地区或权限"
        status == 429 -> "请求太频繁（429），过一会儿再试"
        status >= 500 -> "ChatGPT 暂时不可用（$status）"
        else -> "ChatGPT 出错了" + (if (status > 0) "（$status）" else "") + (code.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
    }

    /** 报错体里的 code 和说明。OAuth 的错是 {error:"…"}，接口的错是 {error:{code,message}}，开流前被拦的可能只有 {detail:"…"}。 */
    private fun errorOf(body: String): Pair<String, String> {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return "" to body.take(200)
        var d = o
        repeat(4) { d = d.optJSONObject("error") ?: d.optJSONObject("detail") ?: return@repeat }
        val code = (d.opt("error") as? String) ?: d.optString("code")
        val msg = d.optString("message").ifBlank { d.optString("error_description") }.ifBlank { (d.opt("detail") as? String).orEmpty() }
        return code to msg
    }

    private fun paramOf(body: String): String = runCatching { JSONObject(body).optJSONObject("error")?.optString("param").orEmpty() }.getOrDefault("")

    private fun diag(what: String, lines: List<String>): String =
        "GPT 控制台 · $what\n" + lines.filter { it.isNotBlank() }.joinToString("\n") +
            "\nApp：第 ${ver.substringAfterLast('.')} 版\n手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · 安卓 ${android.os.Build.VERSION.RELEASE}"

    /** 控制台最下面「诊断」里的整份：现在的状态、最近一次回包的头、最近一次聊天出的错。不含令牌。 */
    fun report(store: AssistantStore): String {
        val left = if (expiresAt > 0) ((expiresAt - System.currentTimeMillis()) / 60000).toString() + " 分钟后要续" else "没有"
        return diag(
            "状态",
            listOf(
                "开关 ${if (on) "开" else "关"} · ${if (signedIn) "已登录" else "没登录"} · 模型 ${models.size} 个",
                "正在用  ${using(store).ifEmpty { "不是它（${store.activeProvider} ${store.activeModel}）" }}",
                "访问令牌  $left",
                "scope  $scopes",
                "注册  ${if (clientId.isEmpty()) "还没有" else "有"} · host ${hostId.takeLast(8)}",
                "—— 最近一次回包的头 ——",
                lastHeaders.ifEmpty { "（还没发过请求）" },
                "—— 最近一次聊天出错 ——",
                lastChat.ifEmpty { "（没有）" },
            ),
        )
    }

    // ───────── 小工具 ─────────

    private class Conf(val authorize: String, val token: String, val jwks: String, val revoke: String)
    @Volatile private var conf: Conf? = null

    /** 登录服务的地址以它自己公布的为准（.well-known），并且都得在 auth.openai.com 下面。 */
    private fun discovery(): Conf {
        conf?.let { return it }
        val r = http("$ISSUER/.well-known/openid-configuration")
        if (r.code != 200) throw Stop("读不到登录配置（HTTP ${r.code}）")
        val o = JSONObject(r.body)
        fun at(k: String, need: Boolean = true): String {
            val v = o.optString(k)
            if (v.isEmpty() && !need) return ""
            if (!v.startsWith("$ISSUER/")) throw Stop("登录配置里的 $k 不在 auth.openai.com 下面")
            return v
        }
        if (o.optString("issuer") != ISSUER) throw Stop("登录配置的签发方不对")
        return Conf(at("authorization_endpoint"), at("token_endpoint"), at("jwks_uri"), at("revocation_endpoint", false)).also { conf = it }
    }

    private class Http(val code: Int, val body: String, val requestId: String)

    private fun http(url: String, form: Map<String, String>? = null, bearer: String? = null): Http {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (form != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.entries.joinToString("&") { e -> enc(e.key) + "=" + enc(e.value) }.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (bearer != null) seen(conn)
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Http(code, body, conn.getHeaderField("x-request-id").orEmpty())
        } finally { runCatching { conn.disconnect() } }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)
    private fun rand() = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
