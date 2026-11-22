package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * DeepSeek 应用内充值。官方没有公开的下单接口（官方客户端 DeepSeek Harness 也是内嵌 /top_up），
 * 所以在看不见的网页里打开已登录的官方充值页，脚本替用户填金额、选付款方式、点支付；
 * 页面跳 weixin:// / alipays:// 时交给系统拉起付款 App。钱付给官方，来源校验由官方页面自己带上。
 * 脚本找不到按钮时把这一页亮出来，让用户自己点完。登录态只存在 WebView 自己的 Cookie / localStorage 里。
 */
internal object DeepSeekPay {
    const val ORIGIN = "https://platform.deepseek.com"
    private const val TOP_UP = "$ORIGIN/top_up"
    private const val SIGN_IN = "$ORIGIN/sign_in"
    /** 脚本自动操作最长等这么久，还没拉起付款 App 就亮出页面。 */
    private const val AUTO_MS = 15_000L

    enum class Step { Amount, Login, Paying, Manual }

    var step by mutableStateOf(Step.Amount)
    var amount by mutableStateOf(20.0)
    var wechat by mutableStateOf(true)
    /** 拉起付款 App 时的余额，回来后比较它判断到账。 */
    var before: Double? = null
    /** 最近一次拉起付款 App 的时间；回到用量页时据此刷新余额。 */
    var launchedAt by mutableLongStateOf(0L)

    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null
    private var startedAt = 0L

    fun supports(c: ProviderConfig) = c.configured && runCatching { Uri.parse(c.base.trim()).host }.getOrNull() == "api.deepseek.com"

    fun open(balance: Double?) {
        before = balance
        step = Step.Amount
        AppSheet.openBare { PaySheet() }
    }

    private fun close() = AppSheet.close()

    private fun release() {
        web?.let { w -> w.stopLoading(); (w.parent as? ViewGroup)?.removeView(w); w.destroy() }
        web = null
    }

    private fun payScheme(u: Uri) = u.scheme != null && u.scheme != "http" && u.scheme != "https" && u.scheme != "about" && u.scheme != "data" && u.scheme != "blob"

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(ctx: android.content.Context): WebView = web ?: WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val u = req?.url ?: return false
                if (!payScheme(u)) return false
                val intent = if (u.scheme == "intent") runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() else Intent(Intent.ACTION_VIEW, u)
                val ok = intent != null && runCatching {
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.component = null
                    intent.selector = null
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    view!!.context.startActivity(intent)
                }.isSuccess
                // 登录页里的「微信授权登录」也会跳 weixin://，那时不算付款
                if (ok && (step == Step.Paying || step == Step.Manual)) { launchedAt = System.currentTimeMillis(); close() }
                return true
            }
        }
        web = this
    }

    private suspend fun js(w: WebView, code: String): String = suspendCancellableCoroutine { c ->
        w.evaluateJavascript(code) { v -> if (c.isActive) c.resume(v.orEmpty()) }
    }

    /** 读 localStorage.userToken：有值就是登录着。 */
    private suspend fun loggedIn(w: WebView): Boolean {
        val v = js(w, "(function(){try{return localStorage.getItem('userToken')||''}catch(e){return ''}})()")
        return v.length > 4 && v != "null"
    }

    private fun script(): String {
        val amt = if (amount % 1.0 == 0.0) amount.toLong().toString() else "%.2f".format(java.util.Locale.US, amount)
        return """
(function(amt, wx){
  function vis(e){ if(!e) return false; var r=e.getBoundingClientRect(); var s=getComputedStyle(e); return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'; }
  function txt(e){ return (e.innerText||e.textContent||'').replace(/\s+/g,''); }
  function all(sel){ return Array.prototype.slice.call(document.querySelectorAll(sel)).filter(vis); }
  function setVal(i,v){ var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'); d.set.call(i,v);
    i.dispatchEvent(new Event('input',{bubbles:true})); i.dispatchEvent(new Event('change',{bubbles:true})); i.dispatchEvent(new Event('blur',{bubbles:true})); }
  var st = window.__amorPay || (window.__amorPay = {amount:false, way:false, submit:0, last:0});
  if (/sign_in|login/.test(location.pathname)) return 'login';
  if (!st.amount) {
    var exact = new RegExp('^[¥￥]?'+amt.replace('.','\\.')+'(\\.0+)?(元|CNY)?$');
    var tile = all('button,label,li,[role=radio],[role=button],div,span').filter(function(e){ return e.children.length<=3 && exact.test(txt(e)); }).pop();
    if (tile) { (tile.closest('button,label,li,[role=radio],[role=button]')||tile).click(); st.amount = true; }
    else {
      var inp = all('input').filter(function(i){ return !/hidden|checkbox|radio|password|email|tel|search/.test(i.type); }).filter(function(i){
        var h=(i.placeholder||'')+(i.getAttribute('aria-label')||'')+(i.name||'');
        return /金额|amount|自定义|其他|custom|元/i.test(h) || i.type==='number' || i.inputMode==='decimal' || i.inputMode==='numeric';
      })[0];
      if (inp) { inp.focus(); setVal(inp, amt); st.amount = true; }
    }
  }
  if (st.amount && !st.way) {
    var re = wx ? /^(微信支付|微信|WeChatPay|WeChat)$/i : /^(支付宝|支付宝支付|Alipay)$/i;
    var way = all('button,label,li,[role=radio],[role=button],div,span,img').filter(function(e){ return re.test(txt(e)) || re.test(e.getAttribute('alt')||''); }).pop();
    if (way) { (way.closest('button,label,li,[role=radio],[role=button]')||way).click(); st.way = true; }
  }
  var now = Date.now();
  if (st.amount && st.way && st.submit < 2 && now - st.last > 1500) {
    var go = all('button,[role=button],a').filter(function(e){ return !e.disabled && e.getAttribute('aria-disabled')!=='true' &&
      /^(立即充值|确认充值|去充值|充值|去支付|立即支付|确认支付|支付|下一步|TopUp|Topup|Pay|PayNow|Next|Continue|Confirm)$/i.test(txt(e)); }).pop();
    if (go) { go.click(); st.submit++; st.last = now; }
  }
  return JSON.stringify(st);
})('$amt', ${wechat});
""".trimIndent()
    }

    /** 金额 → 登录（需要时）→ 后台自动操作 → 拉起付款 App；找不到按钮时亮出页面。 */
    @Composable
    private fun PaySheet() {
        val ctx = LocalContext.current
        val w = remember { webView(ctx) }
        val scope = rememberCoroutineScope()
        DisposableEffect(Unit) { onDispose { release() } }
        // 打开弹层就在后台预加载充值页：顺便知道登没登录，点支付时页面已经就绪
        LaunchedEffect(Unit) { w.loadUrl(TOP_UP) }
        LaunchedEffect(step) {
            when (step) {
                Step.Login -> {
                    if (!(w.url.orEmpty().startsWith(ORIGIN))) w.loadUrl(SIGN_IN)
                    while (step == Step.Login) {
                        delay(1000)
                        if (loggedIn(w)) { w.loadUrl(TOP_UP); step = Step.Paying }
                    }
                }
                Step.Paying -> {
                    startedAt = System.currentTimeMillis()
                    w.evaluateJavascript("delete window.__amorPay", null)
                    if (!w.url.orEmpty().startsWith(TOP_UP)) w.loadUrl(TOP_UP)
                    delay(1200)
                    while (step == Step.Paying) {
                        val result = js(w, script())
                        if (result.contains("login")) { step = Step.Login; break }
                        if (System.currentTimeMillis() - startedAt > AUTO_MS) { step = Step.Manual; break }
                        delay(700)
                    }
                }
                else -> Unit
            }
        }
        val showWeb = step == Step.Login || step == Step.Manual
        val webH by animateDpAsState(if (showWeb) 560.dp else 1.dp, tween(if (showWeb) 420 else 280, easing = if (showWeb) K.decel else K.accel), label = "payWeb")
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            when (step) {
                Step.Amount -> AmountPane(onPay = {
                    scope.launch { step = if (loggedIn(w)) Step.Paying else Step.Login }
                })
                Step.Login -> KSheetTitle("登录 DeepSeek")
                Step.Manual -> KSheetTitle("${if (wechat) "微信" else "支付宝"}支付 ¥${fmt(amount)}")
                Step.Paying -> PayingPane()
            }
            // 同一个 WebView 一直在：自动操作时只露 1dp 且全透明（页面仍按手机尺寸排版、照常运行），登录 / 手动时展开
            Box(Modifier.fillMaxWidth().height(webH).clipToBounds().clip(RoundedCornerShape(14.dp)).graphicsLayer { alpha = if (showWeb) 1f else 0f }) {
                AndroidView(
                    factory = { (w.parent as? ViewGroup)?.removeView(w); w },
                    modifier = Modifier.fillMaxWidth().requiredHeight(560.dp),
                )
            }
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)

    @Composable
    private fun AmountPane(onPay: () -> Unit) {
        var custom by remember { mutableStateOf("") }
        KSheetTitle("充值 · DeepSeek")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10.0, 20.0, 50.0, 100.0).forEach { v ->
                val on = custom.isBlank() && amount == v
                Pressable({ amount = v; custom = "" }, Modifier.weight(1f), label = "¥${fmt(v)}") { pr ->
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) K.acc.copy(alpha = .18f) else if (pr) K.pill else K.card2)
                            .graphicsLayer { scaleX = if (pr) .96f else 1f; scaleY = scaleX },
                        contentAlignment = Alignment.Center,
                    ) { Txt("¥${fmt(v)}", if (on) K.acc else K.ink, 15.sp) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        KField(custom, { s ->
            custom = s.filter { it.isDigit() || it == '.' }.take(8)
            custom.toDoubleOrNull()?.takeIf { it > 0 }?.let { amount = it }
        }, hint = "自定义金额", number = true)
        Spacer(Modifier.height(10.dp))
        KSeg(listOf(true to "微信", false to "支付宝"), { it == wechat }) { wechat = it }
        Spacer(Modifier.height(14.dp))
        val valid = amount > 0 && (custom.isBlank() || (custom.toDoubleOrNull() ?: 0.0) > 0)
        Pressable(onPay, Modifier.fillMaxWidth(), enabled = valid, label = "支付") { pr ->
            Box(
                Modifier.fillMaxWidth().height(50.dp).graphicsLayer { scaleX = if (pr) .98f else 1f; scaleY = scaleX }
                    .clip(RoundedCornerShape(25.dp)).background(if (wechat) Color(0xFF07C160) else Color(0xFF1677FF)),
                contentAlignment = Alignment.Center,
            ) { Txt("${if (wechat) "微信" else "支付宝"}支付 ¥${fmt(amount)}", Color.White, 16.sp) }
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun PayingPane() {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BurstDotsLite()
            Spacer(Modifier.height(14.dp))
            Txt("正在拉起${if (wechat) "微信" else "支付宝"}…", K.ink, 15.sp)
        }
    }

    /** 三个点依次起伏，表示处理中。 */
    @Composable
    private fun BurstDotsLite() {
        val t by produceState(0L) { val s = System.currentTimeMillis(); while (true) { delay(16); value = System.currentTimeMillis() - s } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val ph = ((t + i * 140) % 840) / 840f
                val up = if (ph < .5f) ph * 2 else (1 - ph) * 2
                Box(Modifier.size(7.dp).graphicsLayer { translationY = -up * 10f; alpha = .4f + .6f * up }.clip(RoundedCornerShape(50)).background(K.acc))
            }
        }
    }
}
