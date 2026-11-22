package com.fuguang.lueying

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 官方引擎探测（第 552 版，测试用）：借官方 App 自己的类加载器装载 `io.wallpaperengine.wrapper.SceneLib`，
 * 看它能不能在 Amor 进程里加载 `libscenejni.so`，并把官方接口和引擎回调的那几个类的参数写法列出来。
 * 只读、不调用任何原生函数。每一步先写进文件：万一原生库加载时整个 App 崩了，下次打开还能看到停在哪一步。
 */
internal object WeOfficial {
    private const val PKG = WeEngine.PKG
    private const val LOG = "we-official.txt"

    /** 引擎里回调的 Java 类（从 libscenejni.so 的字符串里查到的） */
    private val CALLBACKS = listOf(
        "io.wallpaperengine.wrapper.SceneLib",
        "io.wallpaperengine.weutil.SupportFileLoader",
        "io.wallpaperengine.weutil.SupportFileLoader\$BitmapFileResponse",
        "io.wallpaperengine.weutil.SupportVideoPlayer",
        "io.wallpaperengine.weutil.VirtualFileRange",
        "io.wallpaperengine.weutil.WallpaperInfoSparse",
    )

    /** 目录：每张正在官方引擎里画的壁纸一个文件，名字是壁纸 id，内容是走到哪一步 */
    private const val RUN = "we-official-run"
    private const val OFF = "we-official-off-554" // 换版本换文件名：旧版崩过的记录不算数
    private const val SCENELIB = "io.wallpaperengine.wrapper.SceneLib"

    /** 进程里只加载一次官方引擎；拿到的是官方 App 自己的 SceneLib 实例和它的方法 */
    private var lib: Any? = null
    private val methods = HashMap<String, Method>()
    /** 在官方引擎里崩过的壁纸：id → 停在哪一步 */
    private val crashed = HashMap<String, String>()
    private var checked = false

    /**
     * 原生代码崩溃抓不住，靠记号：官方引擎在画的时候记号一直留着（[mark]），切后台、换壁纸、收尾才擦（[unmark]）。
     * App 启动后第一次问的时候查一遍：还留着记号的，就是上次画着画着 App 没了，这张以后改用自带渲染器。
     * 只查这一次——之后再看到的记号都是本次正在画的，不是崩溃（第 554 版每次都查，换壁纸时会冤枉正在载入的那张）。
     */
    @Synchronized private fun check(ctx: Context) {
        if (checked) return
        checked = true
        val off = File(ctx.filesDir, OFF); val run = File(ctx.filesDir, RUN)
        runCatching { off.readLines() }.getOrDefault(emptyList()).forEach { l -> if (l.isNotBlank()) crashed[l.substringBefore('|')] = l.substringAfter('|', "") }
        var found = false
        if (run.isFile) { // 第 553、554 版的记号是单个文件「id|步骤」
            val t = runCatching { run.readText() }.getOrDefault("")
            if (t.substringBefore('|').isNotEmpty()) { crashed[t.substringBefore('|')] = t.substringAfter('|', ""); found = true }
            run.delete()
        }
        run.listFiles()?.forEach { f -> crashed[f.name] = runCatching { f.readText() }.getOrDefault(""); f.delete(); found = true }
        if (found) saveOff(ctx)
    }
    private fun saveOff(ctx: Context) { runCatching { File(ctx.filesDir, OFF).writeText(crashed.entries.joinToString("") { it.key + "|" + it.value.replace('\n', ' ') + "\n" }) } }

    /** 这张壁纸能不能走官方引擎：装了官方 App，而且这张没在官方引擎里崩过 */
    @Synchronized fun usable(ctx: Context, wallId: String): Boolean { check(ctx); return WeEngine.apk(ctx) != null && wallId !in crashed }
    /** 这张上次在官方引擎里停在哪一步；没崩过是 null */
    @Synchronized fun crashStep(ctx: Context, wallId: String): String? { check(ctx); return crashed[wallId] }
    /** 长按「官方引擎」后：这张重新试官方引擎 */
    @Synchronized fun retry(ctx: Context, wallId: String) { check(ctx); if (crashed.remove(wallId) != null) saveOff(ctx) }
    fun mark(ctx: Context, wallId: String, step: String) { runCatching { val d = File(ctx.filesDir, RUN); if (d.isFile) d.delete(); d.mkdirs(); File(d, wallId).writeText(step) } }
    fun unmark(ctx: Context, wallId: String) { runCatching { File(File(ctx.filesDir, RUN), wallId).delete() } }

    /** 在 GL 线程上调：借官方 App 的类加载器装 SceneLib，用它自己的 initLibrary 加载 libscenejni.so */
    @Synchronized fun load(app: Context): Any {
        lib?.let { return it }
        val oc = app.createPackageContext(PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val c = Class.forName(SCENELIB, true, oc.classLoader)
        c.declaredMethods.forEach { methods[it.name] = it.apply { isAccessible = true } }
        val l = c.getDeclaredConstructor().newInstance()
        call(l, "initLibrary", OfficialCtx(app, oc))
        lib = l
        return l
    }
    fun call(l: Any, name: String, vararg a: Any?): Any? {
        val m = methods[name] ?: throw NoSuchMethodException("官方引擎没有 $name")
        return try { m.invoke(l, *a) } catch (e: InvocationTargetException) { throw e.targetException ?: e }
    }
    fun ctx(app: Context): Context = OfficialCtx(app, app.createPackageContext(PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY))

    /** 上次没跑完（App 崩了）时留下的记录 */
    fun leftover(ctx: Context): String? = File(ctx.filesDir, LOG).takeIf { it.exists() }?.readText()?.takeIf { "【完】" !in it }

    fun probe(ctx: Context): String {
        val f = File(ctx.filesDir, LOG)
        val sb = StringBuilder()
        fun put(s: String) { sb.append(s).append('\n'); runCatching { f.writeText(sb.toString()) } }
        fun err(t: Throwable) = t.stackTraceToString().lines().take(14).joinToString("\n")
        put("手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · 安卓 ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        put("Amor：${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()}")

        // 1. 官方 App 装在哪、库怎么放的
        val ai: ApplicationInfo = try {
            @Suppress("DEPRECATION") ctx.packageManager.getApplicationInfo(PKG, 0)
        } catch (t: Throwable) { put("1 官方 App：没装（${t.javaClass.simpleName}）"); return done(sb, f) }
        put("1 官方 App：${WeEngine.version(ctx)} · targetSdk ${ai.targetSdkVersion}")
        put("  安装包 ${ai.sourceDir}")
        ai.splitSourceDirs?.let { put("  分包 ${it.joinToString()}") }
        put("  库目录 ${ai.nativeLibraryDir} → ${File(ai.nativeLibraryDir ?: "").list()?.joinToString() ?: "读不到"}")
        put("  extractNativeLibs=${ai.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0}")

        // 2. 官方 App 的上下文和类加载器
        val oc = try {
            ctx.createPackageContext(PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        } catch (t: Throwable) { put("2 官方上下文：失败\n${err(t)}"); return done(sb, f) }
        val cl = oc.classLoader
        put("2 官方上下文：好 · ${cl.javaClass.simpleName}")
        put("  findLibrary(scenejni) = ${runCatching { (cl as? dalvik.system.BaseDexClassLoader)?.findLibrary("scenejni") }.getOrElse { it.toString() }}")

        // 3. 官方的类（只列 io.wallpaperengine.*，好知道还有哪些要看）
        val names = runCatching {
            val all = mutableListOf<String>()
            (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).forEach { p ->
                @Suppress("DEPRECATION") val dx = dalvik.system.DexFile(p)
                dx.entries().asSequence().filter { it.startsWith("io.wallpaperengine.") }.forEach { all += it }
                dx.close()
            }
            all.sorted()
        }.getOrElse { put("3 列类：失败 ${it}"); emptyList() }
        if (names.isNotEmpty()) put("3 官方的类 ${names.size} 个：\n" + names.joinToString("\n") { "  " + it.removePrefix("io.wallpaperengine.") })

        // 4. 接口和回调类的写法（不初始化，不碰原生库）
        val wanted = (CALLBACKS + names.filter { n ->
            val s = n.substringAfterLast('.')
            n.contains(".wrapper.") || n.contains(".weutil.") || Regex("Render|GL|Surface|WallpaperService|Engine|Scene", RegexOption.IGNORE_CASE).containsMatchIn(s)
        }).distinct().filter { '$' !in it.substringAfterLast('.') || it in CALLBACKS }
        put("4 写法：")
        for (n in wanted) {
            val c = try { Class.forName(n, false, cl) } catch (t: Throwable) { put("  ✗ $n：${t.javaClass.simpleName}"); continue }
            put(describe(c))
        }

        // 5. 初始化 SceneLib：它的静态初始化一般会 loadLibrary，在官方的类加载器里加载 libscenejni.so
        put("5 初始化 SceneLib（这一步崩了，下次打开会看到停在这里）…")
        try {
            Class.forName("io.wallpaperengine.wrapper.SceneLib", true, cl)
            put("  初始化：好")
        } catch (t: Throwable) { put("  初始化：失败\n${err(t)}") }
        val maps = runCatching { File("/proc/self/maps").readLines().filter { "scenejni" in it }.map { it.substringAfterLast(' ') }.distinct() }.getOrElse { listOf(it.toString()) }
        put("  进程里的 libscenejni：${if (maps.isEmpty()) "没有（静态初始化没加载它）" else maps.joinToString()}")
        return done(sb, f)
    }

    private fun done(sb: StringBuilder, f: File): String {
        sb.append("【完】")
        runCatching { f.writeText(sb.toString()) }
        return sb.toString()
    }

    private fun describe(c: Class<*>): String = buildString {
        fun t(x: Class<*>): String = if (x.isArray) t(x.componentType!!) + "[]" else x.name.removePrefix("java.lang.").removePrefix("io.wallpaperengine.")
        fun mods(m: Int) = listOfNotNull(if (Modifier.isStatic(m)) "static" else null, if (Modifier.isNative(m)) "native" else null).joinToString(" ")
        append("  ■ ").append(c.name.removePrefix("io.wallpaperengine."))
        c.superclass?.takeIf { it != Any::class.java }?.let { append(" : ").append(t(it)) }
        if (c.interfaces.isNotEmpty()) append(" (").append(c.interfaces.joinToString { t(it) }).append(")")
        append('\n')
        runCatching {
            c.declaredFields.forEach { append("    ${mods(it.modifiers)} ${t(it.type)} ${it.name}\n".replace("     ", "    ")) }
            c.declaredConstructors.forEach { k -> append("    new(${k.parameterTypes.joinToString { t(it) }})\n") }
            c.declaredMethods.sortedBy { it.name }.forEach { m -> append("    ${mods(m.modifiers)} ${t(m.returnType)} ${m.name}(${m.parameterTypes.joinToString { t(it) }})\n".replace("     ", "    ")) }
        }.onFailure { append("    读成员失败：$it\n") }
    }.trimEnd()
}

/**
 * 给官方引擎的 Context：素材、资源、类加载器是官方 App 的（它从这里读着色器、基础贴图），
 * 文件目录、系统服务、权限是 Amor 的（官方的数据目录我们进不去，也不该动）。
 */
private class OfficialCtx(private val app: Context, official: Context) : ContextWrapper(official) {
    private val root = File(app.filesDir, "we-official").apply { mkdirs() }
    private fun sub(n: String) = File(root, n).apply { mkdirs() }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = sub("files")
    override fun getCacheDir(): File = File(app.cacheDir, "we-official").apply { mkdirs() }
    override fun getCodeCacheDir(): File = File(app.codeCacheDir, "we-official").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = sub("nobackup")
    override fun getDataDir(): File = root
    override fun getDir(name: String?, mode: Int): File = sub("app_" + (name ?: "x"))
    override fun getExternalFilesDir(type: String?): File? = app.getExternalFilesDir(type)?.let { File(it, "we-official").apply { mkdirs() } }
    override fun getExternalFilesDirs(type: String?): Array<File> = app.getExternalFilesDirs(type).filterNotNull().map { File(it, "we-official").apply { mkdirs() } }.toTypedArray()
    override fun getExternalCacheDir(): File? = app.externalCacheDir
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = app.getSharedPreferences("we-official-" + (name ?: "x"), Context.MODE_PRIVATE)
    override fun getContentResolver() = app.contentResolver
    override fun getSystemService(name: String): Any? = app.getSystemService(name)
    override fun checkSelfPermission(permission: String): Int = app.checkSelfPermission(permission)
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = app.checkPermission(permission, pid, uid)
}

/**
 * 官方引擎的渲染线程（第 553 版）：照官方 SceneWallpaperView 的顺序——
 * initLibrary → initContext → initScene(文件) → resizeScene → 每帧 updateScene，画在我们自己建的 EGL（ES 3）表面上，
 * 所以还是 TextureView，聊天时的虚化、磨砂照旧。
 * 画的时候一直留着记号；原生代码崩了，下次打开这张改用自带渲染器（[WeOfficial.usable]）。
 * Java 这边抛错（反射找不到、引擎报错）就收尾后回调 [onFail]，由外面换自带渲染器。
 */
internal class WeOfficialThread(
    private val app: Context,
    private val surface: SurfaceTexture,
    @Volatile override var w: Int, @Volatile override var h: Int,
    private val wallId: String,
    private val file: File,
    /** GL 线程上回调：画出第一帧了；宽高比、诊断信息 */
    private val onReady: (aspect: Float, info: String) -> Unit,
    /** 收尾后回调：没起来的原因、停在哪一步 */
    private val onFail: (error: String) -> Unit,
) : Thread("we-official"), WeRunner {
    @Volatile override var playing = true
    @Volatile private var quit = false
    private var step = ""

    override fun finish() { quit = true; interrupt() }

    /** 记号在不在（只在渲染线程上动） */
    private var marked = false
    private fun at(s: String) { step = s; marked = true; WeOfficial.mark(app, wallId, s) }

    override fun run() {
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY; var ctx: EGLContext = EGL14.EGL_NO_CONTEXT; var surf: EGLSurface = EGL14.EGL_NO_SURFACE
        var lib: Any? = null; var id = -1; var fail = ""; var gpu = "未知"
        try {
            at("建 EGL")
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (dpy == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw IllegalStateException("显卡初始化失败")
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            // 3D 模型要深度缓冲，遮罩要模板缓冲（GLSurfaceView 默认也带深度）
            val want = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(dpy, want, 0, cfgs, 0, 1, n, 0) || n[0] < 1) throw IllegalStateException("这台手机不支持 OpenGL ES 3")
            val cfg = cfgs[0]
            ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("这台手机不支持 OpenGL ES 3")
            surf = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("画面建不起来")
            gpu = "${android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_RENDERER)}"

            at("加载官方引擎（initLibrary）")
            val l = WeOfficial.load(app); lib = l
            at("建渲染环境（initContext）")
            id = (WeOfficial.call(l, "initContext", WeOfficial.ctx(app)) as Int)
            // 顺序要紧：Main 在 initScene 里才建，resizeScene 直接用它（第 553 版先 resize，空指针崩了）
            at("载入壁纸（initScene）")
            WeOfficial.call(l, "initScene", id, mpkg(file).path)
            at("设画面大小（resizeScene ${w}×${h}）")
            var sw = w; var sh = h
            WeOfficial.call(l, "resizeScene", id, sw, sh)

            var frames = 0; var ready = false
            val canvas = PointF()
            while (!quit) {
                // 暂停（切后台）时不碰引擎：擦掉记号，这时候 App 被系统收走不算崩
                if (!playing) { if (marked) { marked = false; WeOfficial.unmark(app, wallId) }; try { sleep(80) } catch (_: InterruptedException) {}; continue }
                val now = System.nanoTime()
                if (frames == 0) at("画第一帧（updateScene）") else if (!marked) at(step)
                if (w != sw || h != sh) { sw = w; sh = h; WeOfficial.call(l, "resizeScene", id, sw, sh) }
                WeOfficial.call(l, "updateScene", id)
                if (!EGL14.eglSwapBuffers(dpy, surf)) break // 表面没了
                frames++
                // 场景是另起线程载入的：拿得到画布大小才算载完
                if (!ready && (WeOfficial.call(l, "getSceneCanvasSize", id, canvas) as? Boolean == true) && canvas.x > 0 && canvas.y > 0) {
                    ready = true
                    // 记号不擦：画到一半才崩也要记得住，不然每次打开都崩（第 554 版画出第一帧就擦了）
                    at("已经画出来，画着画着退出的")
                    onReady(canvas.x / canvas.y, "渲染：官方引擎（Wallpaper Engine ${WeEngine.version(app) ?: ""}）\n显卡：$gpu\n画布 ${canvas.x.toInt()}×${canvas.y.toInt()} · 画面 ${sw}×${sh}")
                }
                // 30 秒还没载完：当它没起来
                if (!ready && frames > 900) throw IllegalStateException("30 秒还没载完")
                val left = FRAME_NS - (System.nanoTime() - now)
                if (left > 1_000_000) try { sleep(left / 1_000_000, (left % 1_000_000).toInt()) } catch (_: InterruptedException) {}
            }
        } catch (e: Throwable) {
            if (!quit) fail = "停在：$step\n显卡：$gpu\n" + e.stackTraceToString().lines().take(10).joinToString("\n")
        } finally {
            if (lib != null && id >= 0) {
                runCatching { WeOfficial.call(lib, "shutdownScene", id) }
                runCatching { WeOfficial.call(lib, "destroyContext", id) }
            }
            runCatching {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                    EGL14.eglReleaseThread()
                }
            }
            // 能走到这里就没崩：换壁纸、离开页面、表面没了（折叠屏开合）、引擎报错，都擦掉记号
            WeOfficial.unmark(app, wallId)
            if (fail.isNotEmpty()) onFail(fail)
        }
    }

    /** 官方引擎认文件后缀：给包起一个 .mpkg 的软链接（导入时存成了 .bin） */
    private fun mpkg(f: File): File {
        if (f.name.endsWith(".mpkg")) return f
        val link = File(f.parentFile, f.name.substringBefore('.') + ".mpkg")
        if (!link.exists()) runCatching { android.system.Os.symlink(f.path, link.path) }.onFailure { runCatching { f.copyTo(link, true) } }
        return if (link.exists()) link else f
    }

    private companion object { const val FRAME_NS = 1_000_000_000L / 30 }
}
