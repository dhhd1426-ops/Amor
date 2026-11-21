package com.fuguang.lueying

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.max
import androidx.compose.foundation.layout.navigationBarsPadding

/**
 * 壁纸（第 548 版，原型 KNLw65Bt1Q93XKLzgu4JfF）：「我的 → 壁纸」。导入图片、视频、Wallpaper Engine 手机版导出的 .mpkg，
 * 点一张当场换；只在透明磨砂主题下显示（别的主题整页是实底）。文件都复制进 files/walls/，不上传。
 * 取景（缩放、左右上下）只在壁纸页能动：空白处单指拖、双指捏合；点已选中的那张，界面退开只看壁纸。聊天页固定用调好的构图。
 */
internal class Wall(val id: String, val name: String, val kind: String, val file: String) {
    /**
     * 取景（渲染线程直接读）：缩放、中心 x、中心 y（占整张画面的比例，左上为原点）、旋转（弧度，顺时针）、翻转（0/1）、
     * 对齐（0 覆盖 1 填充 2 自由，照 Wallpaper Engine 手机版）、背景色 r g b（自由模式露出来的地方）。
     */
    val view = floatArrayOf(1f, .5f, .5f, 0f, 0f, 0f, 0f, 0f, 0f)
    /** 播放速度 */
    @Volatile var speed = 1f
    @Volatile var aspect = 16f / 9f
    /** 诊断：画得怎么样、哪里出了问题。长按壁纸看，能复制发给 Claude */
    var diag by mutableStateOf("")
    val path: File get() = File(Walls.dir, file)
    val thumb: File get() = File(Walls.dir, "$id.jpg")
}

internal object Walls {
    private const val KEY = "walls-v1"
    private const val ASKED = "walls-mic-asked"
    var dir: File = File("")
    private var prefs: Prefs? = null
    private var seq = 0

    val list = mutableStateListOf<Wall>()
    /** 空 = 自带的那张 */
    var current by mutableStateOf("")
    /** 正在壁纸页：背景不虚化，聊天页和卡片底色让开 */
    var clear by mutableStateOf(false)
    /** 只看壁纸 */
    var peek by mutableStateOf(false)
    /** 取景动了一下（图片、视频壁纸靠它重排） */
    var tick by mutableIntStateOf(0)
    var undo by mutableStateOf<Pair<Wall, Int>?>(null)
    /** 场景要听声音但还没给权限：进壁纸页时问一次 */
    var needMic by mutableStateOf(false)
    var micOk: (() -> Unit)? = null
    /** 屏幕宽高比 */
    @Volatile var ca = .5f

    private var ver = "?"

    fun init(ctx: Context, p: Prefs) {
        prefs = p
        ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        dir = File(ctx.filesDir, "walls").apply { mkdirs() }
        list.clear()
        runCatching {
            val j = JSONObject(p.string(KEY) ?: "{}")
            val a = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val w = Wall(o.optString("id"), o.optString("name"), o.optString("kind"), o.optString("file"))
                if (w.id.isEmpty() || !w.path.isFile) continue
                w.view[0] = o.optDouble("zoom", 1.0).toFloat().coerceIn(.2f, 8f)
                w.view[1] = o.optDouble("x", .5).toFloat(); w.view[2] = o.optDouble("y", .5).toFloat()
                w.view[3] = o.optDouble("rot", 0.0).toFloat(); w.view[4] = if (o.optBoolean("flip")) 1f else 0f; w.view[5] = o.optInt("align", 0).coerceIn(0, 2).toFloat()
                o.optJSONArray("bg")?.let { for (k in 0..2) w.view[6 + k] = it.optDouble(k, 0.0).toFloat() }
                w.speed = o.optDouble("speed", 1.0).toFloat().coerceIn(.25f, 2f)
                w.aspect = o.optDouble("aspect", 16.0 / 9).toFloat().coerceIn(.2f, 5f)
                list += w
            }
            current = j.optString("cur").takeIf { c -> list.any { it.id == c } } ?: ""
        }
        // 删了没来得及清的文件
        val keep = list.map { it.id }.toSet()
        dir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') !in keep) f.delete() }
    }

    fun now(): Wall? = list.firstOrNull { it.id == current }
    fun name(): String = now()?.name ?: "默认"

    fun save() {
        val a = JSONArray()
        for (w in list) a.put(JSONObject().put("id", w.id).put("name", w.name).put("kind", w.kind).put("file", w.file)
            .put("zoom", w.view[0].toDouble()).put("x", w.view[1].toDouble()).put("y", w.view[2].toDouble()).put("aspect", w.aspect.toDouble())
            .put("rot", w.view[3].toDouble()).put("flip", w.view[4] > .5f).put("align", w.view[5].toInt()).put("speed", w.speed.toDouble())
            .put("bg", JSONArray().put(w.view[6].toDouble()).put(w.view[7].toDouble()).put(w.view[8].toDouble())))
        prefs?.putString(KEY, JSONObject().put("cur", current).put("items", a).toString())
    }

    fun pick(id: String) {
        current = id; save()
        if (!Skin.frost) Skin.set(Skin.FROST) // 别的主题不放背景画面，选了壁纸就换回透明磨砂
    }

    /**
     * 手势：[dx] [dy] 是手指挪过的距离占屏幕的比例，[zoom] 是这一下的倍数，[rot] 是这一下转过的弧度（顺时针为正）。
     * 覆盖：只能在画面里挪、放大 1–4 倍；填充：不动；自由：随便挪、0.2–8 倍、能转，露出背景色（和网页原型一样）。
     */
    fun move(dx: Float, dy: Float, zoom: Float, rot: Float = 0f) {
        val w = now() ?: return
        val v = w.view
        when (v[5].toInt()) {
            1 -> return
            2 -> {
                v[0] = (v[0] * zoom).coerceIn(.2f, 8f); v[3] += rot
                // 手指挪多少画面跟着挪多少：先转回画面自己的方向，再按缩放换算（屏幕高为 1）
                val px = dx * ca; val py = -dy; val cs = kotlin.math.cos(v[3]); val sn = kotlin.math.sin(v[3])
                v[1] -= (cs * px - sn * py) / v[0] / w.aspect; v[2] += (sn * px + cs * py) / v[0]
            }
            else -> {
                v[0] = (v[0] * zoom).coerceIn(1f, 4f)
                val r = WeScene.rect(v, ca, w.aspect)
                v[1] -= dx * r[2]; v[2] -= dy * r[3]
                clamp(w)
            }
        }
        tick++
    }
    /** 覆盖模式：中心夹回画面内，下次从边上往回拖马上有反应 */
    private fun clamp(w: Wall) { val v = w.view; val k = WeScene.rect(v, ca, w.aspect); v[1] = k[0] + k[2] / 2; v[2] = k[1] + k[3] / 2 }

    /** 换对齐方式：从覆盖进自由时画面停在原样不跳 */
    fun align(a: Int) {
        val w = now() ?: return; val v = w.view; val from = v[5].toInt(); if (from == a) return
        val k = max(1f, ca / w.aspect)
        if (a == 2 && from == 0) { v[0] *= k; v[3] = 0f }
        else if (from == 2) { v[0] = (v[0] / k).coerceIn(1f, 4f); v[3] = 0f }
        v[5] = a.toFloat(); if (a == 0) clamp(w)
        tick++; save()
    }
    fun flip() { val w = now() ?: return; w.view[4] = if (w.view[4] > .5f) 0f else 1f; tick++; save() }
    fun speed(s: Float) { val w = now() ?: return; w.speed = s; tick++ }
    fun bg(c: Int) { val w = now() ?: return; w.view[6] = (c shr 16 and 255) / 255f; w.view[7] = (c shr 8 and 255) / 255f; w.view[8] = (c and 255) / 255f; tick++; save() }
    fun reset() { val w = now() ?: return; floatArrayOf(1f, .5f, .5f, 0f, 0f, 0f, 0f, 0f, 0f).copyInto(w.view); w.speed = 1f; tick++; save() }

    fun remove(w: Wall) {
        val i = list.indexOf(w); if (i < 0) return
        undo?.let { purge(it.first) }
        list.removeAt(i); if (current == w.id) current = ""
        undo = w to i; save()
    }
    fun restore() {
        val (w, i) = undo ?: return
        undo = null; list.add(i.coerceIn(0, list.size), w); save()
    }
    fun purge(w: Wall) { dir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') == w.id) f.delete() } }

    /** 诊断框开头：哪张、哪一版、哪台手机 */
    fun diagHead(w: Wall): String =
        "壁纸：${w.name}（${w.kind}）\nApp：第 ${ver.substringAfterLast('.')} 版\n手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · 安卓 ${android.os.Build.VERSION.RELEASE}\n"

    fun micAsked(): Boolean = prefs?.string(ASKED) == "1"
    fun markMicAsked() { prefs?.putString(ASKED, "1") }

    /** 导入一个文件；成功返回 null，失败返回一句原因 */
    suspend fun add(ctx: Context, uri: Uri): String? {
        val id = "w" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (seq++)
        val made = withContext(Dispatchers.IO) {
            val raw = File(dir, "$id.bin"); val thumb = File(dir, "$id.jpg")
            try {
                var name = ""
                runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" } }
                name = name.substringBeforeLast('.').ifEmpty { "壁纸" }
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("读不到这个文件")
                input.use { i -> raw.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                var kind = ""; var file = raw.name; var aspect = 16f / 9f
                if (WePack.looksLike(raw)) {
                    var video: ByteArray? = null
                    WePack(raw).use { p ->
                        val pj = runCatching { JSONObject(p.text("project.json") ?: "{}") }.getOrDefault(JSONObject())
                        pj.optString("title").trim().takeIf { it.isNotEmpty() }?.let { name = it.take(40) }
                        p.bytes(pj.optString("preview").ifEmpty { "preview.jpg" })?.let { thumb.writeBytes(it) }
                        if (p.has("scene.json")) kind = "scene"
                        else if (pj.optString("type").equals("video", true)) {
                            video = p.bytes(pj.optString("file")) ?: throw IllegalArgumentException("这个壁纸包里的视频读不出来")
                            kind = "video"
                        } else throw IllegalArgumentException("这个壁纸包不是场景也不是视频")
                    }
                    video?.let { v -> val f = File(dir, "$id.mp4"); f.writeBytes(v); raw.delete(); file = f.name }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(raw.path, o)
                    if (o.outWidth > 0 && o.outHeight > 0) {
                        kind = "image"; aspect = o.outWidth.toFloat() / o.outHeight
                        var s = 1; while (o.outWidth / s > 800 || o.outHeight / s > 800) s *= 2
                        BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { b ->
                            thumb.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 86, it) }; b.recycle()
                        }
                    } else {
                        val mr = MediaMetadataRetriever()
                        val isVideo = runCatching {
                            mr.setDataSource(raw.path)
                            val yes = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                            if (yes) mr.getFrameAtTime(0)?.let { b -> thumb.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 86, it) }; b.recycle() }
                            yes
                        }.getOrDefault(false)
                        runCatching { mr.release() }
                        if (!isVideo) throw IllegalArgumentException("不认识的文件：只收图片、视频和 .mpkg 壁纸")
                        kind = "video"
                    }
                }
                Wall(id, name, kind, file).also { it.aspect = aspect }
            } catch (e: Throwable) {
                dir.listFiles()?.forEach { f -> if (f.name.substringBefore('.') == id) f.delete() }
                (e as? IllegalArgumentException)?.message ?: "导入失败：存储空间不够，或文件读到一半断了"
            }
        }
        if (made is Wall) { list += made; pick(made.id); return null }
        return made as? String ?: "导入失败"
    }
}

// ---------- 背景层 ----------

/** 主题的背景画面：选了壁纸就放壁纸，没选是自带的那段。换的时候交叉淡入。 */
@Composable
fun WallBackdrop(playing: Boolean, positionX: Float, onFailed: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ca = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        SideEffect { Walls.ca = ca }
        Crossfade(Walls.current, Modifier.fillMaxSize(), tween(460, easing = Curves.standard), label = "wall") { id ->
            val w = Walls.list.firstOrNull { it.id == id }
            if (w == null) SceneBackground(playing = playing, positionX = positionX, onFailed = onFailed)
            else when (w.kind) {
                "scene" -> SceneWall(w, playing)
                "image" -> ImageWall(w)
                "video" -> VideoWall(w, playing)
                else -> SceneBackground(playing = playing, positionX = positionX, onFailed = onFailed)
            }
        }
    }
}

private fun decodeWall(file: File, max: Int): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

@Composable
private fun rememberWallImage(file: File, max: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { decodeWall(file, max) } }
    return img
}

/** 接住 TextureView 的表面，管渲染线程、脚本和频谱的生死。 */
private class WeHost(ctx: Context, private val wall: Wall, private val onReady: (Boolean) -> Unit) : TextureView.SurfaceTextureListener {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scripts = WeScripts(app)
    private val viz = WeViz()
    private var thread: WeThread? = null
    private var audio = false
    private var closed = false
    private val view = { wall.view }
    private val speed = { wall.speed }
    private val bands = { viz.bands() }

    var playing = true
        set(v) { field = v; thread?.playing = v; if (!v) viz.stop() else if (audio) startViz() }

    private fun startViz() {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viz.start()
        else if (!Walls.micAsked()) { Walls.micOk = { viz.start() }; Walls.needMic = true }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        stop()
        val engine = WeEngine.apk(app)
        val engInfo = if (engine != null) "官方素材：已读到（Wallpaper Engine ${WeEngine.version(app) ?: ""}）" else "官方素材：没装官方 Wallpaper Engine，用自带替身"
        val t = WeThread(st, w, h, wall.path, engine, scripts, view, bands, speed) { ok, err, skipped, au, aspect, info0 ->
            val info = info0 + "\n" + engInfo
            main.post {
                if (closed) return@post
                wall.aspect = aspect
                wall.diag = Walls.diagHead(wall) + info + "\n\n" + when {
                    !ok -> "整张没画出来：\n$err"
                    skipped.isEmpty() -> "全部画出来了"
                    else -> "有 ${skipped.size} 处没画出来：\n\n" + skipped.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n")
                }
                if (ok && au && !audio) { audio = true; if (playing) startViz() }
                onReady(ok)
            }
        }
        t.playing = playing; thread = t; t.start()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.let { it.w = w; it.h = h } }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** 收尾限时 1.5 秒，不让界面线程干等 */
    private fun stop() { thread?.let { it.finish(); runCatching { it.join(1500) } }; thread = null }
    fun close() { closed = true; stop(); scripts.close(); viz.stop(); Walls.micOk = null }
}

@Composable
private fun SceneWall(w: Wall, playing: Boolean) {
    val ctx = LocalContext.current
    val poster = rememberWallImage(w.thumb, 1200)
    var ready by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (ready) 1f else 0f, tween(460, easing = Curves.standard), label = "sceneIn")
    val host = remember(w.id) { WeHost(ctx, w) { ok -> ready = ok } }
    DisposableEffect(host) { onDispose { host.close() } }
    LaunchedEffect(host, playing) { host.playing = playing }
    Box(Modifier.fillMaxSize().clipToBounds().background(Page.ground)) {
        // 解包、解贴图要一两秒：先垫包里的预览图
        if (poster != null && a < 1f) Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AndroidView(factory = { c -> TextureView(c).also { it.surfaceTextureListener = host } }, modifier = Modifier.fillMaxSize().alpha(a))
    }
}

/** 按取景框把图片、视频放大、挪位、翻转（超出的部分由外面裁掉）；和场景壁纸的 BLIT_F 同一套规则 */
@Composable
private fun Framed(w: Wall, bw: Dp, bh: Dp, sa: Float, content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") Walls.tick // 取景变了就重排
    val v = w.view
    val flip = Modifier.graphicsLayer { scaleX = if (v[4] > .5f) -1f else 1f }
    when (v[5].toInt()) {
        1 -> Box(Modifier.fillMaxSize().then(flip)) { content() }
        2 -> {
            // 自由：缩放 1 时画面高 = 屏幕高；屏幕中心对着画面上的 (x, y)；绕这一点转；露出来的地方是背景色
            val ch = bh * v[0]; val cw = ch * sa
            Box(Modifier.fillMaxSize().background(Color(v[6], v[7], v[8]))) {
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = Math.toDegrees(v[3].toDouble()).toFloat(); transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center }) {
                    WallPlaced(bw / 2 - cw * v[1], bh / 2 - ch * v[2], cw, ch) { Box(Modifier.fillMaxSize().then(flip)) { content() } }
                }
            }
        }
        else -> {
            val r = WeScene.rect(v, bw.value / bh.value.coerceAtLeast(1f), sa)
            val cw = bw / r[2]; val ch = bh / r[3]
            WallPlaced(-cw * r[0], -ch * r[1], cw, ch) { Box(Modifier.fillMaxSize().then(flip)) { content() } }
        }
    }
}

@Composable
private fun WallPlaced(x: Dp, y: Dp, w: Dp, h: Dp, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val wp = w.roundToPx(); val hp = h.roundToPx()
        val placeables = measurables.map { it.measure(Constraints.fixed(wp, hp)) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(x.toPx().roundToInt(), y.toPx().roundToInt()) } }
    }
}

@Composable
private fun ImageWall(w: Wall) {
    val img = rememberWallImage(w.path, 2400)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Page.ground)) {
        if (img != null) {
            val sa = img.width.toFloat() / img.height.coerceAtLeast(1)
            SideEffect { w.aspect = sa }
            Framed(w, maxWidth, maxHeight, sa) { Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
    }
}

@Composable
private fun VideoWall(w: Wall, playing: Boolean) {
    val context = LocalContext.current
    val poster = rememberWallImage(w.thumb, 1200)
    var ready by remember { mutableStateOf(false) }
    var sa by remember { mutableStateOf(w.aspect) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(w.path)))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { ready = true }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) { sa = videoSize.width.toFloat() / videoSize.height; w.aspect = sa }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(playing) { player.playWhenReady = playing }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Page.ground)) {
        if (!ready && poster != null) Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Framed(w, maxWidth, maxHeight, sa) {
            AndroidView(factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } }, modifier = Modifier.fillMaxSize().alpha(if (ready) 1f else 0f))
        }
    }
}

// ---------- 壁纸页 ----------

private val wallBack = Ic("M14.5 6l-6 6 6 6")
private val wallImport = Ic("M12 5v14M5 12h14")
internal val WallGlyph = Ic("M4 6.5h16v11h-16v-11M4 15l4.5-4 4 3.5 3-2.5 4.5 4", circles = listOf(floatArrayOf(15.5f, 10f, 1.2f)))

@Composable
internal fun WallpaperPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var busy by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        if (us.isNotEmpty()) scope.launch {
            for (u in us) {
                busy++
                val err = runCatching { Walls.add(ctx, u) }.getOrElse { "导入失败" }
                busy--
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show() else haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) Walls.micOk?.invoke(); Walls.micOk = null }
    LaunchedEffect(Walls.needMic) {
        if (Walls.needMic) { Walls.needMic = false; Walls.markMicAsked(); mic.launch(Manifest.permission.RECORD_AUDIO) }
    }
    // 悬浮卡片样式下这一页也铺满：要看得见整张壁纸
    DisposableEffect(Unit) {
        val wasFull = MineCard.full
        if (!Skin.wholeCards) MineCard.full = true
        onDispose { Walls.clear = false; Walls.peek = false; Walls.undo = null; Walls.save(); if (!Skin.wholeCards) MineCard.full = wasFull }
    }
    LaunchedEffect(Skin.id) { Walls.clear = Skin.frost }
    var tune by remember { mutableStateOf(false) }
    LaunchedEffect(Walls.peek) { if (!Walls.peek) tune = false }
    BackHandler(Walls.peek) { if (tune) tune = false else Walls.peek = false }

    val ui by animateFloatAsState(if (Walls.peek) 0f else 1f, tween(if (Walls.peek) 460 else 260, easing = Curves.standard), label = "wallUi")
    val poster = rememberPoster()
    Box(Modifier.fillMaxSize()) {
        // 空白处：单指拖动移画面，双指捏合放大缩小；只看壁纸时轻点一下回到界面
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { if (tune) tune = false else if (Walls.peek) Walls.peek = false } }
                .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, rot -> Walls.move(pan.x / size.width.coerceAtLeast(1), pan.y / size.height.coerceAtLeast(1), zoom, Math.toRadians(rot.toDouble()).toFloat()) } },
        )
        if (ui > 0f) Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth().graphicsLayer { alpha = ui }) {
            Row(Modifier.fillMaxWidth().padding(end = if (Skin.wholeCards) 0.dp else 44.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PressSurface(onBack, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "返回") {
                    SvgIcon(wallBack, Page.ink, 20.dp, stroke = 1.6f, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(6.dp))
                Txt("壁纸", Page.ink, 19.sp, Modifier.weight(1f), spacing = ls(2.6f, 19f), family = Serif, maxLines = 1)
                PressSurface({ pick.launch(arrayOf("*/*")) }, Modifier.size(40.dp), shape = CircleShape, fill = Color.Transparent, pressScale = .9f, label = "导入") {
                    SvgIcon(wallImport, Skin.accent, 21.dp, stroke = 1.7f, modifier = Modifier.align(Alignment.Center))
                }
            }
            // 只有格子这一块能上下滚；它外面的空白留给拖壁纸
            LazyVerticalGrid(
                GridCells.Adaptive(148.dp), Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "default") {
                    WallTile("默认", poster, Walls.current.isEmpty(), onTap = {
                        if (Walls.current.isEmpty()) { if (Skin.frost) Walls.peek = true }
                        else { Walls.pick(""); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }, onHold = null)
                }
                items(Walls.list, key = { it.id }) { w ->
                    WallTile(w.name, rememberWallImage(w.thumb, 640), Walls.current == w.id, onTap = {
                        if (Walls.current == w.id) { if (Skin.frost) Walls.peek = true }
                        else { Walls.pick(w.id); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }, onHold = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        AppSheet.open {
                            KSheetTitle(w.name)
                            DiagBox(w.diag.ifEmpty { Walls.diagHead(w) + "\n" + if (w.kind == "scene") "还没画过：先点它换上" else "图片、视频壁纸不用渲染器" })
                            if (w.kind == "scene") KOption("官方引擎", false) {
                                AppSheet.close()
                                scope.launch {
                                    // 上次探测时 App 崩了：先给看停在哪一步，下次再点才重新探
                                    val left = WeOfficial.leftover(ctx)
                                    val text = if (left != null) { java.io.File(ctx.filesDir, "we-official.txt").delete(); "上次探测时 App 退出了，停在：\n$left" }
                                        else withContext(Dispatchers.IO) { runCatching { WeOfficial.probe(ctx) }.getOrElse { it.stackTraceToString() } }
                                    AppSheet.open { KSheetTitle("官方引擎"); DiagBox(text) }
                                }
                            }
                            KOption("删除", false) { AppSheet.close(); Walls.remove(w) }
                        }
                    })
                }
                if (busy > 0) item(key = "busy") { BusyTile() }
            }
            Spacer(Modifier.size(12.dp))
        }
        WallUndoBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        // 只看壁纸时底部的把手：点或上拉展开壁纸设置（照 Wallpaper Engine 手机版的「常规」）
        Walls.now()?.let { w -> if (Walls.peek) WallTune(w, tune, Modifier.align(Alignment.BottomCenter)) { tune = it } }
    }
}

/** 滑条：点哪儿到哪儿，按住拖；松手回调一次 */
@Composable
private fun TuneSlider(value: Float, from: Float, to: Float, modifier: Modifier, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val change by rememberUpdatedState(onChange); val done by rememberUpdatedState(onDone)
    BoxWithConstraints(modifier.size(30.dp).pointerInput(from, to) {
        fun at(x: Float) = (from + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * (to - from))
        detectTapGestures(onPress = { o -> change(at(o.x)); tryAwaitRelease(); done() })
    }.pointerInput(from, to) {
        fun at(x: Float) = (from + (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * (to - from))
        detectHorizontalDragGestures(onDragEnd = { done() }) { ch, _ -> change(at(ch.position.x)) }
    }, contentAlignment = Alignment.CenterStart) {
        val f = ((value - from) / (to - from)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().size(4.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(2.dp)))
        Box(Modifier.width(maxWidth * f).size(4.dp).background(Skin.accent, RoundedCornerShape(2.dp)))
        Box(Modifier.padding(start = (maxWidth - 18.dp) * f).size(18.dp).background(Skin.accent, CircleShape))
    }
}

private val TUNE_BG = intArrayOf(0x000000, 0xFFFFFF, 0x1C1F26, 0x3A2F2A, 0xE0A988, 0x6F83EA)

@Composable
private fun WallTune(w: Wall, open: Boolean, modifier: Modifier, onOpen: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    @Suppress("UNUSED_EXPRESSION") Walls.tick
    val k by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 420 else 280, easing = if (open) K.decel else K.accel), label = "tune")
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth()
        .graphicsLayer { translationY = (1f - k) * (size.height - 46.dp.toPx()) }
        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
        .background(Color(0xFF1E2129).copy(alpha = .86f * k)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().size(46.dp).pointerInput(Unit) {
            detectTapGestures { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onOpen(!open) }
        }.pointerInput(open) {
            var sum = 0f
            detectVerticalDragGestures(onDragStart = { sum = 0f }, onDragEnd = { if (sum < -30f) onOpen(true) else if (sum > 30f) onOpen(false) }) { _, d -> sum += d }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp, 5.dp).background(Color.White.copy(alpha = .7f), RoundedCornerShape(3.dp)))
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp).navigationBarsPadding().graphicsLayer { alpha = k }) {
            val align = w.view[5].toInt()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .085f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("覆盖", "填充", "自由").forEachIndexed { i, t ->
                    Pressable({ Walls.align(i); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, Modifier.weight(1f), label = t) { pr ->
                        Box(Modifier.fillMaxWidth().size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (align == i) Color.White.copy(alpha = .14f) else Color.Transparent).scale(if (pr) .96f else 1f), contentAlignment = Alignment.Center) {
                            Txt(t, if (align == i) Color(0xFFECEEF2) else Color(0x8FECEEF2), 15.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Pressable({ Walls.flip(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, Modifier.fillMaxWidth(), label = "翻转") { _ ->
                Row(Modifier.fillMaxWidth().size(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("翻转", Color(0xFFECEEF2), 15.5.sp, Modifier.weight(1f), maxLines = 1)
                    val on = w.view[4] > .5f
                    val x by animateFloatAsState(if (on) 1f else 0f, tween(240, easing = K.decel), label = "flip")
                    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) Skin.accent else Color.White.copy(alpha = .16f))) {
                        Box(Modifier.padding(3.dp).graphicsLayer { translationX = x * 18.dp.toPx() }.size(20.dp).background(Color.White, CircleShape))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().size(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("速度", Color(0xFFECEEF2), 15.5.sp, Modifier.width(56.dp), maxLines = 1)
                var sp by remember(w.id) { mutableStateOf(w.speed) }
                TuneSlider(sp, .25f, 2f, Modifier.weight(1f), { sp = it; Walls.speed(it) }) { Walls.save() }
                Txt((Math.round(sp * 100) / 100f).toString().removeSuffix(".0") + "×", Color(0x8FECEEF2), 14.sp, Modifier.width(44.dp).padding(start = 8.dp), maxLines = 1)
            }
            if (align == 2) Row(Modifier.fillMaxWidth().size(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("背景色", Color(0xFFECEEF2), 15.5.sp, Modifier.weight(1f), maxLines = 1)
                TUNE_BG.forEach { c ->
                    val on = Math.round(w.view[6] * 255) == (c shr 16 and 255) && Math.round(w.view[7] * 255) == (c shr 8 and 255) && Math.round(w.view[8] * 255) == (c and 255)
                    Box(Modifier.padding(start = 10.dp).size(26.dp).then(if (on) Modifier.border(2.dp, Skin.accent, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = .25f), CircleShape))
                        .padding(if (on) 3.dp else 0.dp).background(Color(0xFF000000.toInt() or c), CircleShape)
                        .pointerInput(c) { detectTapGestures { Walls.bg(c); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } })
                }
            }
            Pressable({ Walls.reset(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, Modifier.fillMaxWidth(), label = "重置") { _ ->
                Box(Modifier.fillMaxWidth().size(52.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { Txt("重置", Skin.accent, 15.5.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun WallTile(name: String, img: ImageBitmap?, on: Boolean, onTap: () -> Unit, onHold: (() -> Unit)?) {
    var pressed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val sc by animateFloatAsState(if (pressed) .97f else 1f, tween(if (pressed) 70 else 180, easing = Curves.standard), label = "tile")
    val k by animateFloatAsState(if (on) 1f else 0f, spring(.5f, 500f), label = "tileTick")
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).scale(sc).clip(shape).background(Skin.cardFill(.5f))
            .then(if (on) Modifier.border(2.dp, Skin.accent, shape) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onLongPress = { hold?.invoke() },
                    onTap = { tap() },
                )
            },
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x9E080A0E))))
                .padding(start = 12.dp, end = 12.dp, top = 26.dp, bottom = 11.dp),
        ) { Txt(name, Color.White, 13.5.sp, maxLines = 1) }
        if (k > .01f) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(26.dp).scale(k).background(Skin.accent, CircleShape), contentAlignment = Alignment.Center) {
            DrawnCheck(Skin.onAccent, 14.dp)
        }
    }
}

/**
 * 诊断框：等宽字、能选中，右上角「复制」。出问题时用户截图或复制发来就能定位。
 * 以后新功能都照这个做（问题指南「诊断框」一节）。
 */
@Composable
internal fun DiagBox(text: String) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .28f))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("诊断", K.sub, 12.sp, Modifier.weight(1f), maxLines = 1)
            Pressable({
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("诊断", text))
                copied = true
            }, label = "复制") { pr ->
                Txt(if (copied) "已复制" else "复制", K.acc, 13.sp, Modifier.clip(RoundedCornerShape(50)).background(K.acc.copy(alpha = if (pr) .22f else 0f)).padding(horizontal = 12.dp, vertical = 8.dp), maxLines = 1)
            }
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Txt(text, K.ink.copy(alpha = .88f), 12.sp, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), lineHeight = 18.sp, family = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

/** 正在导入的那一格：还没有图，底色一明一暗 */
@Composable
private fun BusyTile() {
    val t = rememberInfiniteTransition(label = "busy")
    val a by t.animateFloat(.25f, .6f, infiniteRepeatable(tween(700, easing = Curves.standard), RepeatMode.Reverse), label = "busyA")
    Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(20.dp)).background(Skin.cardFill(a)))
}

/** 删掉后 4 秒内能撤销（照 MemUndoBar）；过了才真的删文件。 */
@Composable
private fun WallUndoBar(modifier: Modifier) {
    val u = Walls.undo
    LaunchedEffect(u) { if (u != null) { delay(4000); if (Walls.undo === u) { Walls.undo = null; withContext(Dispatchers.IO) { Walls.purge(u.first) } } } }
    AnimatedVisibility(
        u != null, modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(420, easing = K.decel)) { it },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(280, easing = K.accel)) { it },
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(K.pill).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt("已删除", K.ink, 14.sp, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Pressable({ Walls.restore() }, label = "撤销") { pr ->
                Txt("撤销", K.acc, 14.sp, Modifier.clip(RoundedCornerShape(50)).background(K.acc.copy(alpha = if (pr) .26f else .16f)).padding(horizontal = 14.dp, vertical = 7.dp), maxLines = 1)
            }
        }
    }
}
