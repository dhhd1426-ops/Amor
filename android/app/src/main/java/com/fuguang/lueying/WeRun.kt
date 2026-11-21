package com.fuguang.lueying

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.audiofx.Visualizer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 场景壁纸的渲染线程（第 548 版）：在 TextureView 的表面上自己建 EGL（ES 3），每秒 30 帧画 [WeScene]。
 * 用 TextureView 不用 SurfaceView：聊天时背景要跟着虚化、磨砂卡片要透出它，这两样都只对画进界面层的内容起作用。
 * 云朵天气的教训：时钟每帧最多走 0.25 秒（切后台回来不快进）；收尾限时，不让界面线程干等。
 */
internal class WeThread(
    private val surface: SurfaceTexture,
    @Volatile var w: Int, @Volatile var h: Int,
    private val file: File,
    private val texts: WeTexts?,
    private val view: () -> FloatArray,
    private val bands: () -> FloatArray?,
    /** GL 线程上回调：ok、出错的话是原因、跳过的图层、场景要不要听声音、场景宽高比、诊断信息（显卡、图层数、包版本） */
    private val onState: (ok: Boolean, error: String, skipped: List<String>, audio: Boolean, aspect: Float, info: String) -> Unit,
) : Thread("we-gl") {
    @Volatile var playing = true
    @Volatile private var quit = false

    fun finish() { quit = true; interrupt() }

    override fun run() {
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        var pack: WePack? = null; var scene: WeScene? = null; var gpu = "未知"
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw IllegalStateException("显卡初始化失败")
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            val want = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(dpy, want, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw IllegalStateException("这台手机不支持 OpenGL ES 3")
            val cfg = cfgs[0]
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("这台手机不支持 OpenGL ES 3")
            surf = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) throw IllegalStateException("画面建不起来")
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("画面建不起来")
            gpu = "${android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_RENDERER)} · ${android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_VERSION)}"

            pack = WePack(file)
            scene = WeScene(pack, texts)
            scene.load(h)

            var clock = 0.0; var last = -1L; var first = true; var reported = 0
            while (!quit) {
                if (!playing) { last = -1L; try { sleep(80) } catch (_: InterruptedException) {}; continue }
                val now = System.nanoTime()
                if (last > 0) clock += min(.25, (now - last) / 1e9)
                last = now
                scene.frame(clock.toFloat(), max(1, w), max(1, h), view(), bands())
                if (!EGL14.eglSwapBuffers(dpy, surf)) break // 表面没了
                if (first || scene.skipped.size != reported) { first = false; reported = scene.skipped.size; onState(true, "", ArrayList(scene.skipped), scene.wantsAudio, scene.aspect, "显卡：$gpu\n包：${pack.version} · ${scene.total} 个图层 · 渲染 ${scene.size[0]}×${scene.size[1]}") }
                val left = FRAME_NS - (System.nanoTime() - now)
                if (left > 1_000_000) try { sleep(left / 1_000_000, (left % 1_000_000).toInt()) } catch (_: InterruptedException) {}
            }
        } catch (e: Throwable) {
            if (!quit) onState(false, (e.message ?: e.javaClass.simpleName).trim().take(600), emptyList(), false, 16f / 9f, "显卡：$gpu\n包：${pack?.version ?: "没打开"}")
        } finally {
            runCatching { scene?.release() }
            runCatching { pack?.close() }
            runCatching {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                    EGL14.eglReleaseThread()
                }
            }
        }
    }

    private companion object { const val FRAME_NS = 1_000_000_000L / 30 }
}

/**
 * 文字层的脚本（时钟、日期这类）：壁纸自带的 SceneScript 在一个没挂到界面上、断了网、不许读文件的 WebView 里跑，
 * 每半秒问一次各层现在该显示什么。脚本是创意工坊作者写的，所以关在 WebView 的沙盒进程里，不给任何原生接口。
 */
internal class WeScripts(ctx: Context) : WeTexts {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val vals = ConcurrentHashMap<String, String>()
    private val pending = ArrayList<String>()
    private var web: WebView? = null
    private var ready = false
    @Volatile private var alive = true

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            val w = web
            if (w != null && ready) runCatching {
                w.evaluateJavascript("__we_tick()") { r ->
                    runCatching {
                        val s = JSONTokener(r ?: "null").nextValue() as? String ?: return@runCatching
                        val j = JSONObject(s)
                        for (k in j.keys()) vals[k] = j.optString(k)
                    }
                }
            }
            main.postDelayed(this, 500)
        }
    }

    init {
        main.post {
            if (!alive) return@post
            runCatching {
                @SuppressLint("SetJavaScriptEnabled")
                val w = WebView(app).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            ready = true
                            for (js in pending) view?.evaluateJavascript(js, null)
                            pending.clear()
                        }
                    }
                    loadDataWithBaseURL(null, "<html><head><script>$PRELUDE</script></head><body></body></html>", "text/html", "utf-8", null)
                }
                web = w
                main.postDelayed(tick, 300)
            }
        }
    }

    override fun register(id: String, script: String, props: String, initial: String) {
        val js = "__we_add(${JSONObject.quote(id)},${JSONObject.quote(script)},$props,${JSONObject.quote(initial)})"
        main.post {
            if (!alive) return@post
            val w = web
            if (w != null && ready) runCatching { w.evaluateJavascript(js, null) } else pending += js
        }
    }

    override fun value(id: String): String? = vals[id]

    fun close() {
        alive = false
        main.post { main.removeCallbacks(tick); runCatching { web?.destroy() }; web = null }
    }

    private companion object {
        // 照 we.js 的 scriptOf：去掉 export，给一个 createScriptProperties，场景里的 scriptproperties 盖掉默认值
        const val PRELUDE = """
var __we={};
function __we_add(id,src,over,init){try{
var make=function(){var props={};var b={};
var add=function(o){props[o.name]=('value' in o)?o.value:(o.options&&o.options[0]?o.options[0].value:undefined);return b;};
['addCheckbox','addText','addCombo','addSlider','addColor','addFile','addDirectory'].forEach(function(k){b[k]=add;});
b.finish=function(){for(var k in over){var v=over[k];props[k]=(v&&typeof v==='object'&&('value' in v))?v.value:v;}return props;};return b;};
var body=src.replace(/^\s*export\s+/gm,'')+'\n;return {update:typeof update==="function"?update:null};';
__we[id]={s:(new Function('createScriptProperties',body))(make),v:init};
}catch(e){}}
function __we_tick(){var o={};for(var id in __we){try{var e=__we[id];if(e.s&&e.s.update)o[id]=String(e.s.update(e.v));}catch(x){}}return JSON.stringify(o);}
"""
    }
}

/**
 * 跟着声音动（照云朵天气的 Viz.java）：系统的 Visualizer 挂在全局输出（session 0）上，读的是手机「正在播放」的声音的频谱，
 * 不录麦克风、不存、不上传。安卓把它算作录音权限，调用前要先确认有权限。
 * 频谱按 30 Hz–16 kHz 对数分成 64 段，0–1；一秒没新数据（没在放声音或被系统停了）就当静音。
 */
internal class WeViz : Visualizer.OnDataCaptureListener {
    private var v: Visualizer? = null
    private val out = FloatArray(64)
    @Volatile private var at = 0L

    @Synchronized
    fun start(): Boolean {
        if (v != null) return true
        return try {
            val z = Visualizer(0)
            z.enabled = false
            val r = Visualizer.getCaptureSizeRange()
            z.captureSize = max(r[0], min(1024, r[1]))
            runCatching { z.scalingMode = Visualizer.SCALING_MODE_NORMALIZED } // 音量调小也照样有节奏
            z.setDataCaptureListener(this, min(Visualizer.getMaxCaptureRate(), 30000), false, true)
            z.enabled = true
            v = z; true
        } catch (_: Throwable) { stop(); false }
    }

    @Synchronized
    fun stop() {
        v?.let { z -> runCatching { z.enabled = false }; runCatching { z.release() } }
        v = null; at = 0
    }

    /** GL 线程每帧来取 */
    fun bands(): FloatArray? {
        if (v == null || SystemClock.uptimeMillis() - at > 1000) return null
        synchronized(out) { return out.copyOf() }
    }

    override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

    override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
        if (fft == null || fft.size < 8) return
        val n = fft.size / 2
        val sr = samplingRate / 1000.0; val bw = sr / fft.size // samplingRate 是毫赫兹
        if (bw <= 0) return
        val lo = 30.0; val hi = min(16000.0, sr / 2 - bw)
        if (hi <= lo) return
        val tmp = FloatArray(64)
        for (i in 0 until 64) {
            val f0 = lo * (hi / lo).pow(i / 64.0); val f1 = lo * (hi / lo).pow((i + 1) / 64.0)
            val k0 = max(1, floor(f0 / bw).toInt()); val k1 = min(n - 1, max(k0 + 1, ceil(f1 / bw).toInt()))
            var m = 0.0
            var k = k0
            while (k < k1 && k < n) { m = max(m, hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())); k++ }
            val db = 20 * log10(m + 1e-3) // 一格最大约 181 → 45 dB
            tmp[i] = ((db - 6) / 36).coerceIn(0.0, 1.0).toFloat()
        }
        synchronized(out) { System.arraycopy(tmp, 0, out, 0, 64) }
        at = SystemClock.uptimeMillis()
    }
}
