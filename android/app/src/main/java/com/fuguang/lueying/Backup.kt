package com.fuguang.lueying

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份：存进「下载 / 浮光掠影」，不弹选文件夹。
 * - 备份数据（.fgly）：全部设置文件（对话、助理、记忆、手记、供应商和密钥、MCP、语音、鲸鱼娘、播放进度）
 *   + App 自己的文件（对话记录、作品、图片、壁纸、技能、鲸鱼娘模型……）+ 手表授权 + 网页登录（Cookie、本地存储）。
 *   临时文件、缓存、日志不装（[SKIP]）。用户定的：自己换手机用，密钥照带，不做「不带密钥」的选项。
 * - 打包（.zip）：上面那个 .fgly + 浮光掠影本身的安装包，换手机先装 App 再恢复。
 * - 恢复（第 561 版起）：[stage] 把备份解到暂存目录 → [commit] 重启 → 新进程一起来（[FgApp]，任何页面、服务之前）[applyStaged] 换进去。
 *   不能边跑边换：设置已经读进内存了，当场换文件会被内存里的旧值写回去。
 */
object Backup {
    const val FOLDER = "浮光掠影"
    private const val LAST = "backup-last"

    /** 上次备份：时间（毫秒）和大小（字节）；没备份过是 null。 */
    var last by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p
        last = p.string(LAST)?.split("|")?.let { runCatching { it[0].toLong() to it[1].toLong() }.getOrNull() }
    }

    class Result(val uri: Uri, val name: String, val bytes: Long)
    class NoRoom(val short: Long) : Exception()

    /**
     * files/ 下不进备份的：下载和种子的临时文件、解压临时目录、可以重新拉的内容、日志和运行记号。恢复时这些也不动。
     * 鲸鱼娘的东西全带（用户点名要的）：模型 whale/model、设置、配好音的台词 whale_voice（重配要花钱）；只有导入时的临时目录 whale/tmp 不带。
     */
    private val SKIP = listOf("torrent", "downloads", "whale/tmp", "asr", "tdlib", "content", "we-official", "we-official.txt", "crash.txt", "reply-runtime.log", "adblock.txt")
    private fun skipped(rel: String) = SKIP.any { rel == it || rel.startsWith("$it/") }
    /** 设置文件里不带的：官方壁纸引擎自己的、WebView 自己的（跟着手机走）。 */
    private fun prefSkipped(name: String) = name.startsWith("we-official-") || name.startsWith("WebView")
    /** 网页登录只带这两样（充值页、网盘、内置浏览器的登录）；别的都是缓存。 */
    private val WEB = listOf("Default/Cookies", "Default/Cookies-journal", "Default/Local Storage")
    private const val STAGE = "restore-staged"

    private fun dataFiles(ctx: Context): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val data = File(ctx.applicationInfo.dataDir)
        File(data, "shared_prefs").listFiles()?.filter { it.isFile && it.name.endsWith(".xml") && !prefSkipped(it.name) }?.sortedBy { it.name }
            ?.forEach { out += "shared_prefs/" + it.name to it }
        val root = ctx.filesDir
        root.walkTopDown().onEnter { it == root || !skipped(it.relativeTo(root).path) }.filter { it.isFile }.forEach {
            val rel = it.relativeTo(root).path
            if (!skipped(rel)) out += "files/$rel" to it
        }
        ctx.noBackupFilesDir.listFiles()?.filter { it.isFile && it.name.startsWith("suunto-") }?.forEach { out += "no_backup/" + it.name to it }
        val web = File(data, "app_webview")
        WEB.forEach { w ->
            val f = File(web, w)
            if (f.isFile) out += "webview/$w" to f
            else if (f.isDirectory) f.walkTopDown().filter { it.isFile }.forEach { out += "webview/" + it.relativeTo(web).path to it }
        }
        return out
    }

    private fun apk(ctx: Context) = File(ctx.applicationInfo.sourceDir)

    /** 两种备份大概多大（字节）：小卡片上显示。 */
    suspend fun sizes(ctx: Context): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val d = dataFiles(ctx).sumOf { it.second.length() }
        d to d + apk(ctx).length()
    }

    /** 做一份备份。step 报告做到哪一步（给进度圈和步骤字用）。 */
    suspend fun run(ctx: Context, pack: Boolean, step: suspend (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        // 先让还在排队的设置写进文件
        listOf("fuguang", "voice", "whale", "dl-play").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit() }
        val files = dataFiles(ctx)
        val need = files.sumOf { it.second.length() } + (if (pack) apk(ctx).length() else 0L)
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        if (free < need + 4_000_000) throw NoRoom(need + 4_000_000 - free)

        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date())
        val name = if (pack) "浮光掠影-打包-$stamp.zip" else "浮光掠影-$stamp.fgly"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (pack) "application/zip" else "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("下载文件夹打不开")
        try {
            resolver.openOutputStream(uri)!!.use { os ->
                if (!pack) writeData(files, os, step)
                else ZipOutputStream(os).use { z ->
                    z.putNextEntry(ZipEntry("浮光掠影-$stamp.fgly"))
                    writeData(files, NoClose(z), step)
                    z.closeEntry()
                    step(2)
                    z.putNextEntry(ZipEntry("浮光掠影-安装包.apk"))
                    apk(ctx).inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            step(if (pack) 3 else 2)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        val bytes = resolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else need } ?: need
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            last = now to bytes
            prefs?.putString(LAST, "$now|$bytes")
        }
        Result(uri, name, bytes)
    }

    /** 数据部分：设置文件一步，作品和图片一步。 */
    private suspend fun writeData(files: List<Pair<String, File>>, os: OutputStream, step: suspend (Int) -> Unit) {
        ZipOutputStream(os).use { z ->
            z.putNextEntry(ZipEntry("fgly.txt"))
            // 第二行是备份的时刻（毫秒），恢复前给你看是哪天的
            z.write("浮光掠影备份 2\n${System.currentTimeMillis()}\n".toByteArray())
            z.closeEntry()
            val (sp, rest) = files.partition { it.first.startsWith("shared_prefs/") }
            sp.forEach { (n, f) -> z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            step(0)
            rest.forEach { (n, f) -> z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            step(1)
        }
    }

    // ---------- 恢复 ----------

    /** 暂存好的一份备份：多少个文件、多大、里面有没有对话记录。 */
    class Staged(val files: Int, val bytes: Long, val prefs: Int, val at: Long)
    class NotBackup : Exception("这不是浮光掠影的备份")

    private fun stageDir(ctx: Context) = File(ctx.applicationInfo.dataDir, STAGE)

    /** 把选中的 .fgly 或打包的 .zip 解到暂存目录（还没换进去）。打包的 zip 里那个安装包跳过。 */
    suspend fun stage(ctx: Context, uri: Uri): Staged = withContext(Dispatchers.IO) {
        val dir = stageDir(ctx)
        dir.deleteRecursively(); dir.mkdirs()
        var files = 0; var bytes = 0L; var prefs = 0; var mark = false; var at = 0L
        fun take(z: java.util.zip.ZipInputStream) {
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                if (e.isDirectory) continue
                if (n == "fgly.txt") { mark = true; at = z.readBytes().toString(Charsets.UTF_8).lines().getOrNull(1)?.trim()?.toLongOrNull() ?: 0L; continue }
                if (n.endsWith(".fgly")) { take(java.util.zip.ZipInputStream(NoCloseIn(z))); continue }
                val top = n.substringBefore('/')
                if (top !in setOf("shared_prefs", "files", "no_backup", "webview")) continue
                val out = File(dir, n)
                // 压缩包里的路径不许跑出暂存目录
                if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { o -> bytes += z.copyTo(o) }
                files++; if (top == "shared_prefs") prefs++
            }
        }
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { ins -> java.util.zip.ZipInputStream(ins.buffered(1 shl 16)).use { take(it) } }
        } catch (e: Throwable) { dir.deleteRecursively(); throw e }
        if (!mark || prefs == 0) { dir.deleteRecursively(); throw NotBackup() }
        Staged(files, bytes, prefs, at)
    }

    /** 不恢复了：暂存的删掉。 */
    fun discard(ctx: Context) { runCatching { stageDir(ctx).deleteRecursively() } }

    /** 确认恢复：做上记号，重启。新进程一起来就换进去（[applyStaged]）。 */
    fun commit(ctx: Context) {
        File(stageDir(ctx), "ready").writeText("1")
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        Runtime.getRuntime().exit(0)
    }

    /**
     * 进程一起来就调（[FgApp]）：有做了记号的暂存备份就换进去。分两步各做记号，换到一半被杀了下次接着换，不会把已经换好的又删掉。
     * 返回这次是不是刚恢复完。
     */
    fun applyStaged(ctx: Context): Boolean {
        val dir = stageDir(ctx)
        if (!File(dir, "ready").exists()) { if (dir.exists()) dir.deleteRecursively(); return false }
        val data = File(ctx.applicationInfo.dataDir)
        runCatching {
            val root = ctx.filesDir
            val wiped = File(dir, "wiped")
            if (!wiped.exists()) {
                // 原来的文件先清掉（临时文件、缓存那些不动），免得两份数据混在一起
                root.listFiles()?.forEach { if (!skipped(it.name)) it.deleteRecursively() }
                wiped.writeText("1")
            }
            fun move(from: File, to: File) {
                from.walkTopDown().filter { it.isFile }.toList().forEach { f ->
                    val t = File(to, f.relativeTo(from).path)
                    t.parentFile?.mkdirs(); t.delete()
                    if (!f.renameTo(t)) { f.copyTo(t, overwrite = true); f.delete() }
                }
            }
            val sp = File(data, "shared_prefs")
            File(dir, "shared_prefs").listFiles()?.forEach { File(sp, it.name + ".bak").delete() }
            move(File(dir, "shared_prefs"), sp)
            move(File(dir, "files"), root)
            move(File(dir, "no_backup"), ctx.noBackupFilesDir)
            move(File(dir, "webview"), File(data, "app_webview"))
        }
        dir.deleteRecursively()
        return true
    }

    /** 交给系统分享：发到网盘、发给自己。 */
    fun share(ctx: Context, r: Result) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (r.name.endsWith(".zip")) "application/zip" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, r.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "发给…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun mb(bytes: Long): String = if (bytes < 1_000_000) "%.1f MB".format(Locale.US, bytes / 1_000_000.0).let { if (bytes < 100_000) "0.1 MB" else it } else if (bytes < 10_000_000) "%.1f MB".format(Locale.US, bytes / 1_000_000.0) else "${bytes / 1_000_000} MB"

    /** 「刚刚备份 · 3.8 MB」「10月17日备份 · 3.8 MB」。 */
    fun lastLine(): String {
        val (t, b) = last ?: return "还没有备份过"
        val gap = System.currentTimeMillis() - t
        val w = when {
            gap < 60_000 -> "刚刚"
            gap < 86_400_000 -> ago(t)
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))
        }
        return "${w}备份 · ${mb(b)}"
    }

    /** 读外层 zip 里的内层 zip：内层读完别把外层也关了。 */
    private class NoCloseIn(private val i: java.io.InputStream) : java.io.InputStream() {
        override fun read() = i.read()
        override fun read(b: ByteArray, off: Int, len: Int) = i.read(b, off, len)
        override fun close() {}
    }

    /** 包在外层 zip 里写内层 zip：内层关闭时别把外层也关了。 */
    private class NoClose(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() { flush() }
    }
}
