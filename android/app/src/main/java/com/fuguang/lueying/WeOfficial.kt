package com.fuguang.lueying

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
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
