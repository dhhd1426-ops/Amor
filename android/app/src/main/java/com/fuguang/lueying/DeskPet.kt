package com.fuguang.lueying

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 桌宠（原型 Qeg3WLSG8EkjaDFLNmHRwe 第 4 版）：Amor 不在前台时，鲸鱼娘浮在所有界面上。
 *
 * - 一个悬浮窗（TYPE_APPLICATION_OVERLAY）只包住她自己，里面是单独的一个透明 WebView，页面同主页那份（assets/whale，带 desk=1）：
 *   页面自己把她缩放到窗口里（whale.js 的 DESK 段），不用主页存的位置，也不自己拖。
 * - 手指全在这边：一碰就把 down 转给页面（她看过来）；挪过一点就是拖窗口（页面那次按下作废），松手贴边；
 *   往屏幕边上甩或拖出去一截就贴边收起，只露半个，点一下弹回来。没挪就是点，页面按点在哪（头、脸、身体、尾巴）给反应。
 * - 她说的话只有一个气泡：页面不画（desk 模式藏掉），这边另开一个不接触摸的小窗画，字一个个出来，说完自己收。
 *   安卓 12 起，盖在别的 App 上又不接触摸的窗口要半透明（≤ 0.8）下面才点得动，所以气泡窗是 0.8。
 * - 回到 Amor 就藏起来（主页上已经有她）；藏一分钟还没出来就拆掉 WebView 省内存。息屏停帧。
 * - 进程靠 PactKeeper 常驻（wanted 里算上桌宠），划掉最近任务她也还在。
 */
object DeskPet {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private var root: FrameLayout? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var ready = false
    private var fitted = false
    /** 窗口现在挂着、看得见 */
    private var shown = false
    /** Amor 在前台（MainActivity 的 onStart 到 onStop）；只有常驻服务、没有界面时是 false */
    private var front = false
    private var screenOn = true
    private var hooked = false
    private var docked = false
    private var left = false
    private var yFrac = .55f
    /** 她在窗口里占的范围（比例 l,t,r,b），用来放气泡 */
    private var box = floatArrayOf(.2f, .1f, .8f, .95f)
    private var retried = false

    /** 诊断框（「我的 › 鲸鱼娘」桌宠下面） */
    var log by mutableStateOf(""); private set
    private val lines = ArrayDeque<String>()
    private fun note(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$t $s"); while (lines.size > 16) lines.removeFirst()
        log = lines.joinToString("\n")
    }

    fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** 设置里开了桌宠、也允许了悬浮窗（PactKeeper 据此常驻）。Whale 没初始化时直接读它的设置文件。 */
    fun wanted(ctx: Context): Boolean = canDraw(ctx) && runCatching {
        JSONObject(ctx.getSharedPreferences("whale", Context.MODE_PRIVATE).getString("set", null) ?: "{}").optBoolean("desk", false)
    }.getOrDefault(false)

    /** 去系统里允许「显示在其他应用上层」；小米先试它自己的权限页。 */
    fun askPermission(ctx: Context) {
        val pkg = ctx.packageName
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (i in tries) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    private fun bind(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
        if (wm == null) wm = app.getSystemService(WindowManager::class.java)
        if (hooked) return
        hooked = true
        load()
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(c: Configuration) { main.post { if (root != null) { place(); hideBubble() } } }
            @Deprecated("") override fun onLowMemory() {}
        })
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { screenOn = i.action != Intent.ACTION_SCREEN_OFF; sync(c) }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
    }

    /** MainActivity 的 onStart / onStop */
    fun front(ctx: Context, on: Boolean) {
        bind(ctx)
        front = on
        main.removeCallbacks(out)
        if (on) { sync(ctx); return }
        if (wanted(ctx)) PactKeeper.sync(ctx)
        // 折叠屏开合、转屏时界面会先 stop 再马上 start：等一下再出来，免得她闪一下
        main.postDelayed(out, 350)
    }
    private val out = Runnable { if (!front) sync(app) }

    private val drop = Runnable { if (!shown) teardown("藏了一分钟，先拆掉省内存") }

    fun sync(ctx: Context) {
        bind(ctx)
        Whale.ensure(app)
        val want = Whale.desk && Whale.hasModel && !Whale.broken && canDraw(app) && !front
        if (want && Whale.prepping) { main.postDelayed({ sync(app) }, 5000); return }
        if (want && screenOn) show()
        else {
            hide()
            main.removeCallbacks(drop)
            if (!want) teardown(null) else main.postDelayed(drop, 60_000)
        }
    }

    /** 设置改了推给桌面那份 */
    fun set(json: String) = js("whale.set($json)")

    private fun js(s: String) { val w = web ?: return; if (ready) main.post { w.evaluateJavascript(s, null) } }

    // —— 窗口 ——
    private val d get() = app.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).roundToInt()
    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) wm!!.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else app.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun show() {
        if (root == null) build() ?: return
        val r = root ?: return
        main.removeCallbacks(drop)
        if (!shown) {
            shown = true
            r.visibility = View.VISIBLE
            lp?.let { it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv(); runCatching { wm?.updateViewLayout(r, it) } }
            place()
            web?.onResume(); js("whale.pause(false)")
        }
    }

    private fun hide() {
        hideBubble()
        if (!shown) return
        shown = false
        val r = root ?: return
        r.visibility = View.GONE
        lp?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; runCatching { wm?.updateViewLayout(r, it) } }
        js("whale.pause(true)"); web?.onPause()
    }

    private fun teardown(why: String?) {
        hideBubble()
        bub?.let { b -> runCatching { wm?.removeView(b) } }; bub = null; bubTv = null
        val r = root ?: return
        if (why != null) note(why)
        runCatching { wm?.removeView(r) }
        web?.destroy()
        root = null; web = null; lp = null; ready = false; fitted = false; shown = false
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun build(): Unit? {
        val w = wm ?: return null
        val pet = object : WebView(app) {
            override fun dispatchTouchEvent(e: MotionEvent) = false
        }.apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            alpha = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Bridge(), "Vg")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = Whale.serve(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    note("渲染进程没了（${if (detail.didCrash()) "崩溃" else "被系统收走"}）")
                    main.post {
                        teardown(null)
                        if (!retried) { retried = true; main.postDelayed({ sync(app) }, 3000) }
                    }
                    return true
                }
            }
        }
        val holder = object : FrameLayout(app) {
            override fun onInterceptTouchEvent(e: MotionEvent) = true
            override fun onTouchEvent(e: MotionEvent) = touch(e)
        }
        holder.addView(pet)
        val p = WindowManager.LayoutParams(
            dp(150f), dp(190f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "Amor 桌宠" }
        val ok = runCatching { w.addView(holder, p) }
        if (ok.isFailure) { note("悬浮窗加不上：${ok.exceptionOrNull()?.let { it.javaClass.simpleName + " " + (it.message ?: "") }}"); return null }
        root = holder; web = pet; lp = p; shown = true; ready = false; fitted = false
        place()
        note("开始载入")
        pet.loadUrl(Whale.deskUrl())
        main.postDelayed({ if (web === pet && !ready) note("半分钟还没站出来") }, 30_000)
        return Unit
    }

    private class Bridge {
        @JavascriptInterface
        fun event(name: String, json: String) { main.post { onEvent(name, json) } }
    }

    private fun onEvent(name: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val w = web ?: return
        when (name) {
            "ready" -> {
                ready = true; retried = false
                note("已就绪")
                w.evaluateJavascript("whale.set(${Whale.setJsonText()})", null)
                if (!shown) { w.evaluateJavascript("whale.pause(true)", null); w.onPause() }
            }
            "fit" -> if (!fitted) {
                fitted = true
                note("已放进窗口")
                w.scaleX = .9f; w.scaleY = .9f; w.pivotX = w.width / 2f; w.pivotY = w.height.toFloat()
                w.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
            }
            "box" -> {
                val b = floatArrayOf(o.optDouble("l").toFloat(), o.optDouble("t").toFloat(), o.optDouble("r").toFloat(), o.optDouble("b").toFloat())
                if (b[2] > b[0] && b[3] > b[1]) box = b
            }
            "speak" -> { val t = o.optString("text"); if (shown) { bubble(t); Speech.whaleSay(t) } }
            "error" -> { note("载入失败：" + o.optString("msg").ifBlank { json }.take(400)); teardown(null) }
            "warn" -> note("小错：" + o.optString("msg").take(200))
            "glLost" -> { note("显存不够，画布丢了"); teardown(null) }
        }
    }

    // —— 位置：靠哪边、上下比例、是不是贴边收着 ——
    private fun sp() = app.getSharedPreferences("whale", Context.MODE_PRIVATE)
    private fun load() {
        val s = sp().getString("desk-pos", null)?.split(',') ?: return
        left = s.getOrNull(0) == "l"; yFrac = s.getOrNull(1)?.toFloatOrNull() ?: .55f; docked = s.getOrNull(2) == "1"
    }
    private fun save() { sp().edit().putString("desk-pos", "${if (left) "l" else "r"},$yFrac,${if (docked) 1 else 0}").apply() }

    private fun targetX(sw: Int, w: Int): Int {
        val peek = (w * .42f).roundToInt(); val m = dp(6f)
        return if (docked) { if (left) -(w - peek) else sw - peek } else { if (left) m else sw - w - m }
    }
    private fun yRange(sh: Int, h: Int) = dp(28f) to maxOf(dp(28f), sh - h - dp(28f))

    private fun place() {
        val p = lp ?: return; val r = root ?: return
        val (sw, sh) = screen()
        val (y0, y1) = yRange(sh, p.height)
        p.x = targetX(sw, p.width); p.y = (y0 + (y1 - y0) * yFrac).roundToInt()
        r.alpha = if (docked) .82f else 1f
        runCatching { wm?.updateViewLayout(r, p) }
    }

    private var anim: ValueAnimator? = null
    private fun glide(tx: Int, ty: Int) {
        val p = lp ?: return; val r = root ?: return
        anim?.cancel()
        val x0 = p.x; val y0 = p.y
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { a ->
                val k = a.animatedValue as Float
                p.x = (x0 + (tx - x0) * k).roundToInt(); p.y = (y0 + (ty - y0) * k).roundToInt()
                if (root === r) runCatching { wm?.updateViewLayout(r, p) }
            }
            start()
        }
    }

    // —— 手指 ——
    private var rx = 0f; private var ry = 0f; private var px0 = 0; private var py0 = 0
    private var dragging = false
    private var vt: VelocityTracker? = null

    private fun touch(e: MotionEvent): Boolean {
        val p = lp ?: return false; val r = root ?: return false; val w = web ?: return false
        val fx = if (w.width > 0) e.x / w.width else .5f
        val fy = if (w.height > 0) e.y / w.height else .5f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                rx = e.rawX; ry = e.rawY; px0 = p.x; py0 = p.y; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                if (!docked) js("whale.ptr('down',$fx,$fy)")
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val mx = e.rawX - rx; val my = e.rawY - ry
                if (!dragging && hypot(mx, my) > ViewConfiguration.get(app).scaledTouchSlop) {
                    dragging = true
                    if (!docked) js("whale.cancel()")
                    hideBubble()
                    r.animate().scaleX(1.06f).scaleY(1.06f).alpha(1f).setDuration(160).start()
                }
                if (dragging) {
                    p.x = px0 + mx.roundToInt(); p.y = py0 + my.roundToInt()
                    runCatching { wm?.updateViewLayout(r, p) }
                    vt?.let { it.computeCurrentVelocity(1); r.rotation = (it.xVelocity / d * 10f).coerceIn(-12f, 12f) }
                } else if (!docked) js("whale.ptr('move',$fx,$fy)")
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = vt?.let { it.computeCurrentVelocity(1); it.xVelocity } ?: 0f
                vt?.recycle(); vt = null
                when {
                    dragging -> { settle(v); r.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(260).start() }
                    docked -> { docked = false; val (sw, _) = screen(); glide(targetX(sw, p.width), p.y); r.animate().alpha(1f).setDuration(200).start(); save() }
                    else -> js("whale.ptr('up',0,0)")
                }
                dragging = false
            }
        }
        return true
    }

    private fun settle(vx: Float) {
        val p = lp ?: return; val r = root ?: return
        val (sw, sh) = screen(); val w = p.width
        left = p.x + w / 2 < sw / 2
        val past = if (left) p.x < -w * .18f else p.x > sw - w + w * .18f
        val fling = if (left) vx < -1.4f * d else vx > 1.4f * d
        docked = past || fling
        val (y0, y1) = yRange(sh, p.height)
        val ty = p.y.coerceIn(y0, y1)
        yFrac = if (y1 > y0) (ty - y0).toFloat() / (y1 - y0) else .5f
        glide(targetX(sw, w), ty)
        r.animate().alpha(if (docked) .82f else 1f).setDuration(260).start()
        save()
    }

    // —— 气泡：她说的话，一个泡，说完自己收 ——
    private var bub: FrameLayout? = null
    private var bubTv: TextView? = null
    private var tail: View? = null
    private var bubLp: WindowManager.LayoutParams? = null
    private var bubTok = 0

    private fun bubble(text: String) {
        val t = text.trim(); val p = lp ?: return
        if (t.isEmpty() || dragging) return
        val w = wm ?: return
        val bg = Skin.sheet.copy(alpha = 1f).toArgb()
        if (bub == null) {
            val tv = TextView(app).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setLineSpacing(0f, 1.25f)
                setPadding(dp(13f), dp(8f), dp(13f), dp(8f)); maxWidth = dp(220f); gravity = Gravity.CENTER
                elevation = dp(3f).toFloat()
            }
            val tl = View(app).apply { rotation = 45f; elevation = dp(3f).toFloat() }
            val f = FrameLayout(app).apply { clipChildren = false; clipToPadding = false; addView(tl, FrameLayout.LayoutParams(dp(11f), dp(11f))); addView(tv) }
            val bl = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; alpha = .8f; title = "Amor 桌宠气泡" }
            if (runCatching { w.addView(f, bl) }.isFailure) return
            bub = f; bubTv = tv; tail = tl; bubLp = bl
        }
        val f = bub!!; val tv = bubTv!!; val tl = tail!!; val bl = bubLp!!
        tv.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(15f).toFloat() }
        tl.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(2f).toFloat() }
        tv.setTextColor(Skin.ink.toArgb())
        // 先按整句量好大小，打字时框不跟着变
        tv.text = t
        tv.measure(View.MeasureSpec.makeMeasureSpec(dp(220f), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val bw = tv.measuredWidth; val bh = tv.measuredHeight; val pad = dp(8f)
        val ww = bw + pad * 2; val wh = bh + pad * 2
        val (sw, _) = screen()
        val cx = p.x + p.width * (box[0] + box[2]) / 2f
        val head = p.y + p.height * box[1]
        val bx = (cx - ww / 2f).roundToInt().coerceIn(dp(8f), maxOf(dp(8f), sw - ww - dp(8f)))
        var by = (head - wh + pad / 2f).roundToInt()
        val below = by < dp(28f)
        if (below) by = p.y + p.height - pad / 2
        tv.layoutParams = FrameLayout.LayoutParams(bw, bh).apply { leftMargin = pad; topMargin = pad }
        val tx = (cx - bx).roundToInt().coerceIn(pad + dp(14f), ww - pad - dp(14f))
        tl.layoutParams = FrameLayout.LayoutParams(dp(11f), dp(11f)).apply {
            leftMargin = tx - dp(5.5f); topMargin = if (below) pad - dp(5.5f) else pad + bh - dp(5.5f)
        }
        bl.width = ww; bl.height = wh; bl.x = bx; bl.y = by
        runCatching { w.updateViewLayout(f, bl) }
        f.visibility = View.VISIBLE
        f.pivotX = tx.toFloat(); f.pivotY = if (below) 0f else wh.toFloat()
        f.animate().cancel()
        f.alpha = 0f; f.scaleX = .92f; f.scaleY = .92f; f.translationY = if (below) -dp(6f).toFloat() else dp(6f).toFloat()
        f.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(320).setInterpolator(PathInterpolator(.05f, .7f, .1f, 1f)).start()
        val tok = ++bubTok
        tv.text = ""
        for (i in t.indices) main.postDelayed({ if (tok == bubTok) tv.text = t.substring(0, i + 1) }, i * 90L)
        main.postDelayed({ if (tok == bubTok) hideBubble() }, t.length * 90L + 1660)
    }

    private fun hideBubble() {
        bubTok++
        val f = bub ?: return
        if (f.visibility != View.VISIBLE) return
        f.animate().cancel()
        val tok = bubTok
        f.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(180).setInterpolator(PathInterpolator(.3f, 0f, .8f, .15f))
            .withEndAction { if (tok == bubTok) f.visibility = View.GONE }.start()
    }

    /** 诊断框里的全文 */
    fun diag(ctx: Context): String = buildString {
        appendLine("桌宠诊断")
        appendLine("机型 ${Build.MANUFACTURER} ${Build.MODEL} · 安卓 ${Build.VERSION.RELEASE}（${Build.VERSION.SDK_INT}）")
        appendLine("App ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"}")
        appendLine("悬浮窗权限 ${if (canDraw(ctx)) "有" else "没有"} · 开关 ${if (Whale.desk) "开" else "关"} · 模型 ${if (Whale.hasModel) "有" else "没有"}${if (Whale.broken) " · 已停用" else ""}")
        appendLine("窗口 ${if (root != null) (if (shown) "显示中" else "藏着") else "没建"} · 页面 ${if (ready) "就绪" else "未就绪"} · ${if (docked) "贴边收起" else "展开"}")
        if (log.isNotEmpty()) append(log)
    }.trimEnd()
}
