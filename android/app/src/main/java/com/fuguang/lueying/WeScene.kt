package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Wallpaper Engine 场景渲染器（第 548 版，OpenGL ES 3）。照网页参考实现 we.js 一一对应搬过来的，结构不要另起：
 * 读包 → 建图层 → 每层一条效果链（两张缓冲来回倒）→ 画进场景缓冲 → 按取景框铺到屏幕。
 * 包里的着色器原样用，只在前面加一段方言头。所有方法都在 GL 线程调用。
 * 坐标约定：所有贴图和缓冲统一「v=0 是图的顶」。细节见项目文档《壁纸-渲染器笔记》。
 */
internal class WeScene(private val pack: WePack, private val texts: WeTexts?) {
    class Tex(var id: Int, var w: Int, var h: Int, var fb: Int = 0)
    private class U(val name: String, val type: Int, val loc: Int, val ann: JSONObject?)
    private class Prog(val p: Int, val us: List<U>)
    private class Pass(val prog: Prog, val values: JSONObject, val texNames: List<String?>, val binds: List<Pair<Int, String>>, val target: String?)
    private class Fx(val fbos: Map<String, Float>, val passes: List<Pass>)
    private class Layer(val o: JSONObject) {
        var kind = ""; var tex: Tex? = null
        var size = floatArrayOf(1f, 1f); var origin = floatArrayOf(0f, 0f, 0f); var scale = floatArrayOf(1f, 1f, 1f)
        var angle = 0f; var alpha = 1f; var color = floatArrayOf(1f, 1f, 1f); var brightness = 1f
        val fx = ArrayList<Fx>(); val named = HashMap<String, Tex>()
        var a: Tex? = null; var b: Tex? = null; var bad = false
        // 文字层
        var px = 40f; var pad = 0f; var fixed = ""; var str: String? = null; var align = "center"; var id = ""
    }
    private class Ctx(val tex: Array<Tex?>, val values: JSONObject? = null, val mvp: FloatArray? = null, val extra: Map<String, FloatArray>? = null)

    /** 跳过的图层（一层坏了只跳过这一层，不连累别的） */
    val skipped = ArrayList<String>()
    var aspect = 16f / 9f; private set
    /** 场景里有几个图层（诊断用） */
    val total: Int get() = layers.size
    /** 场景里有没有听声音的效果（有才去读频谱） */
    var wantsAudio = false; private set

    private var W = 1920f; private var H = 1080f; private var q = 1f
    /** 场景缓冲的大小（诊断用） */
    val size: IntArray get() = if (::sceneBuf.isInitialized) intArrayOf(sceneBuf.w, sceneBuf.h) else intArrayOf(0, 0)
    private var clear = floatArrayOf(0f, 0f, 0f)
    private val layers = ArrayList<Layer>()
    private val textures = HashMap<String, Tex>()
    private val cache = HashMap<String, Prog>()
    private val all = ArrayList<Tex>()
    private lateinit var generic: Prog
    private lateinit var sceneBuf: Tex
    private var copyProg = 0; private var blitProg = 0
    private var full = 0; private var unit = 0
    private var time = 0f
    private val audio = HashMap<Int, Array<FloatArray>>().apply { for (n in intArrayOf(16, 32, 64)) put(n, arrayOf(FloatArray(n), FloatArray(n))) }

    // ---------- 小工具 ----------
    private fun unwrap(v: Any?): Any? = if (v is JSONObject && v.has("value")) v.opt("value") else if (v == JSONObject.NULL) null else v
    private fun nums(v0: Any?): FloatArray? {
        val v = unwrap(v0)
        return when (v) {
            is Number -> floatArrayOf(v.toFloat())
            is Boolean -> floatArrayOf(if (v) 1f else 0f)
            is String -> v.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.isNotEmpty() }
            else -> null
        }
    }
    private fun vec(v: Any?, d: FloatArray): FloatArray = nums(v) ?: d
    private fun num(v: Any?, d: Float): Float = nums(v)?.firstOrNull() ?: d
    private fun why(e: Throwable) = (e.message ?: e.javaClass.simpleName).trim().take(600)
    private fun text(path: String): String? = pack.text(path) ?: BUILTIN[path]
    private fun json(path: String): JSONObject = JSONObject(text(path) ?: throw IllegalStateException("包里没有 $path"))

    // ---------- 贴图和缓冲 ----------
    private fun mkTex(w: Int, h: Int, data: ByteArray?, repeat: Boolean = false, etc2: Boolean = false): Tex {
        val a = IntArray(1); GLES30.glGenTextures(1, a, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a[0])
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        if (etc2 && data != null) GLES30.glCompressedTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_COMPRESSED_RGBA8_ETC2_EAC, w, h, 0, data.size, ByteBuffer.wrap(data))
        else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, data?.let { ByteBuffer.wrap(it) })
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val wr = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wr)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wr)
        return Tex(a[0], w, h).also { all += it }
    }
    private fun mkTarget(w0: Float, h0: Float): Tex {
        val w = max(1, w0.roundToInt()); val h = max(1, h0.roundToInt())
        val t = mkTex(w, h, null); val a = IntArray(1); GLES30.glGenFramebuffers(1, a, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, a[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t.id, 0)
        t.fb = a[0]; return t
    }
    private fun texture(name: String): Tex? {
        textures[name]?.let { return it }
        val b = pack.bytes("materials/$name.tex") ?: return null
        val img = WeTex.decode(b, 2048)
        return mkTex(img.w, img.h, img.data, etc2 = img.etc2).also { textures[name] = it }
    }

    // ---------- 着色器 ----------
    private fun compile(type: Int, src: String, name: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(s).trim(); GLES30.glDeleteShader(s); throw IllegalStateException("着色器编译失败 $name\n$log") }
        return s
    }
    private fun link(vs: String, fs: String, name: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs, "$name.vert"); val f = compile(GLES30.GL_FRAGMENT_SHADER, fs, "$name.frag")
        val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "a_Position"); GLES30.glBindAttribLocation(p, 1, "a_TexCoord"); GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetProgramInfoLog(p).trim(); GLES30.glDeleteProgram(p); throw IllegalStateException("着色器链接失败 $name\n$log") }
        return p
    }
    /** HLSL 习惯写法（云朵天气 wall.js 里验证过）：1.0f；把 texSample2D 的结果直接赋给 float；组合开关当布尔用在 ?: 里 */
    private fun hlsl(src0: String, combos: Collection<String>): String {
        var src = src0.replace(F_SUFFIX1, "$1").replace(F_SUFFIX2, "$1.0").replace(TEX_TO_FLOAT, "$1$2.r;")
        for (k in combos) if (IDENT_ONLY.matches(k)) src = src.replace(Regex("([=(,:?]\\s*)" + k + "\\s*\\?"), "$1($k != 0) ?")
        return src
    }
    private fun expand(src: String, seen: HashSet<String>): String = INCLUDE.replace(src) { m ->
        val f = m.groupValues[1]
        if (!seen.add(f)) "" else INCLUDES[f] ?: throw IllegalStateException("缺头文件 $f")
    }

    /** shader：如 effects/shake；combos：材质和场景给的开关；given：哪些贴图槽给了图（带 combo 的槽据此开关） */
    private fun program(shader: String, combos: JSONObject?, given: BooleanArray): Prog {
        val v0 = text("shaders/$shader.vert"); val f0 = text("shaders/$shader.frag")
        if (v0 == null || f0 == null) throw IllegalStateException("包里没有着色器 $shader")
        val both = v0 + "\n" + f0
        val defs = LinkedHashMap<String, Float>(); val ann = HashMap<String, JSONObject>()
        for (m in COMBO.findAll(both)) runCatching { val j = JSONObject(m.groupValues[1]); defs[j.getString("combo")] = num(j.opt("default"), 0f) }
        for (m in UNIFORM.findAll(both)) runCatching {
            val j = JSONObject(m.groupValues[3]); val n = m.groupValues[2]; ann[n] = j
            val slot = SLOT.matchEntire(n)
            if (slot != null && j.has("combo")) defs[j.getString("combo")] = if (given.getOrElse(slot.groupValues[1].toInt()) { false }) 1f else 0f
        }
        if (combos != null) for (k in combos.keys()) defs[k] = num(combos.opt(k), 0f)
        val vs = hlsl(expand(v0, HashSet()), defs.keys); val fs = hlsl(expand(f0, HashSet()), defs.keys)
        // #if 里用到但没人定义的名字一律当 0（GLSL ES 里未定义的名字会报错，WE 的编译器当 0）
        val defined = HashSet<String>(); for (m in DEFINE.findAll(HEAD + vs + fs)) defined += m.groupValues[1]
        for (m in IFLINE.findAll(vs + "\n" + fs)) for (id in IDENT.findAll(m.groupValues[1])) {
            val k = id.value
            if (k != "defined" && k !in defined && k !in defs) defs[k] = 0f
        }
        val dstr = StringBuilder()
        for ((k, v) in defs) if (IDENT_ONLY.matches(k)) dstr.append("#define ").append(k).append(' ').append(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()).append('\n')
        val key = "$shader|$dstr"
        cache[key]?.let { return it }
        val p = link(VHEAD + dstr + vs, FHEAD + dstr + fs, shader)
        val n = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_ACTIVE_UNIFORMS, n, 0)
        val us = ArrayList<U>(); val sz = IntArray(1); val ty = IntArray(1)
        for (i in 0 until n[0]) {
            val raw = GLES20.glGetActiveUniform(p, i, sz, 0, ty, 0) ?: continue
            val name = raw.removeSuffix("[0]")
            us += U(name, ty[0], GLES30.glGetUniformLocation(p, raw), ann[name])
        }
        return Prog(p, us).also { cache[key] = it }
    }
    private fun rawProg(frag: String): Int = link(RAW_V, frag, "raw")

    private fun quad(d: FloatArray): Int {
        val a = IntArray(1); GLES30.glGenVertexArrays(1, a, 0); GLES30.glBindVertexArray(a[0])
        val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
        val fb = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); fb.put(d); fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        return a[0]
    }

    // ---------- 读场景 ----------
    /** [surfaceH]：屏幕高（像素）。场景缓冲的高度跟屏幕走，540–1080 之间（高刷大屏全分辨率跑模糊太费电）。 */
    fun load(surfaceH: Int) {
        val scene = json("scene.json")
        val general = scene.optJSONObject("general") ?: JSONObject()
        val ortho = general.optJSONObject("orthogonalprojection")
        W = (ortho?.optDouble("width", 1920.0) ?: 1920.0).toFloat(); H = (ortho?.optDouble("height", 1080.0) ?: 1080.0).toFloat()
        if (W < 1f || H < 1f) { W = 1920f; H = 1080f }
        aspect = W / H
        val sh = min(1080, max(540, surfaceH)); q = sh / H
        clear = vec(general.opt("clearcolor"), floatArrayOf(0f, 0f, 0f))

        textures["util/white"] = mkTex(1, 1, byteArrayOf(-1, -1, -1, -1))
        textures["util/black"] = mkTex(1, 1, byteArrayOf(0, 0, 0, -1))
        textures["util/noflow"] = mkTex(1, 1, byteArrayOf(127, 127, 0, -1))
        val noise = ByteArray(256 * 256 * 4); java.util.Random(7).nextBytes(noise)
        textures["util/noise"] = mkTex(256, 256, noise, repeat = true)

        full = quad(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))         // 缓冲之间：第 0 行就是图的顶
        unit = quad(floatArrayOf(-.5f, -.5f, 0f, 1f, .5f, -.5f, 1f, 1f, -.5f, .5f, 0f, 0f, .5f, .5f, 1f, 0f)) // 画进场景：顶边 v=0
        copyProg = rawProg(COPY_F); blitProg = rawProg(BLIT_F)
        generic = program("genericimage2", null, booleanArrayOf(true))
        sceneBuf = mkTarget(W * q, sh.toFloat())

        val objects = scene.optJSONArray("objects") ?: JSONArray()
        for (i in 0 until objects.length()) {
            val o = objects.optJSONObject(i) ?: continue
            try { build(o)?.let { layers += it } }
            catch (e: Throwable) { skipped += (o.optString("name").ifEmpty { o.optString("id") }) + "：" + why(e) }
        }
    }

    private fun build(o: JSONObject): Layer? {
        val l = Layer(o)
        l.id = o.optString("id")
        l.origin = vec(o.opt("origin"), floatArrayOf(W / 2, H / 2, 0f)).let { if (it.size >= 2) it else floatArrayOf(W / 2, H / 2, 0f) }
        l.scale = vec(o.opt("scale"), floatArrayOf(1f, 1f, 1f)).let { if (it.size >= 2) it else floatArrayOf(it[0], it[0], 1f) }
        l.angle = vec(o.opt("angles"), floatArrayOf(0f, 0f, 0f)).getOrElse(2) { 0f }
        l.alpha = num(o.opt("alpha"), 1f); l.brightness = num(o.opt("brightness"), 1f)
        l.color = vec(o.opt("color"), floatArrayOf(1f, 1f, 1f)).let { if (it.size >= 3) it else floatArrayOf(1f, 1f, 1f) }
        val image = unwrap(o.opt("image")) as? String
        val txt = o.opt("text")
        if (image != null) {
            when {
                image.contains("util/composelayer") || image.contains("util/fullscreenlayer") || image.contains("util/projectlayer") -> l.kind = "compose"
                image.contains("util/solidlayer") -> { l.kind = "solid"; l.tex = textures["util/white"] }
                else -> {
                    l.kind = "image"
                    val mat = json(json(image).getString("material"))
                    val tn = mat.getJSONArray("passes").getJSONObject(0).getJSONArray("textures").getString(0)
                    l.tex = texture(tn) ?: throw IllegalStateException("缺贴图 $tn")
                }
            }
            val t = l.tex
            l.size = vec(o.opt("size"), if (t != null) floatArrayOf(t.w.toFloat(), t.h.toFloat()) else floatArrayOf(W, H)).let { if (it.size >= 2) it else floatArrayOf(W, H) }
        } else if (txt != null && txt != JSONObject.NULL) {
            l.kind = "text"
            val tj = txt as? JSONObject
            l.fixed = if (tj != null) (unwrap(tj.opt("value"))?.toString() ?: "") else txt.toString()
            l.px = num(o.opt("pointsize"), 10f) * 16f / 3f // 1pt = 4/3 px，再按 4 倍场景算；和官方预览图量出来的字高一致
            l.pad = num(o.opt("padding"), 0f)
            l.align = (unwrap(o.opt("horizontalalign")) as? String) ?: "center"
            l.tex = mkTex(1, 1, byteArrayOf(0, 0, 0, 0))
            val script = tj?.optString("script").orEmpty()
            if (script.isNotEmpty()) texts?.register(l.id, script, (tj?.optJSONObject("scriptproperties") ?: JSONObject()).toString(), l.fixed)
        } else return null

        val effects = o.optJSONArray("effects") ?: JSONArray()
        for (i in 0 until effects.length()) {
            val e = effects.optJSONObject(i) ?: continue
            if (unwrap(e.opt("visible")) == false) continue
            try { effect(l, e) } catch (err: Throwable) { skipped += (o.optString("name").ifEmpty { l.id }) + " › " + e.optString("file") + "：" + why(err) }
        }
        return l
    }

    private fun effect(l: Layer, e: JSONObject) {
            val def = json(e.getString("file"))
            val fbos = HashMap<String, Float>()
            def.optJSONArray("fbos")?.let { fa -> for (k in 0 until fa.length()) fa.optJSONObject(k)?.let { fbos[it.optString("name")] = it.optDouble("scale", 1.0).toFloat().coerceAtLeast(1f) } }
            val passes = ArrayList<Pass>()
            val dps = def.getJSONArray("passes"); val insts = e.optJSONArray("passes")
            for (k in 0 until dps.length()) {
                val dp = dps.getJSONObject(k); val inst = insts?.optJSONObject(k) ?: JSONObject()
                val mp = json(dp.getString("material")).getJSONArray("passes").getJSONObject(0)
                val names = ArrayList<String?>(); val given = BooleanArray(8); given[0] = true
                inst.optJSONArray("textures")?.let { ta -> for (t in 0 until ta.length()) { val n = ta.opt(t); val s = if (n is String && n.isNotEmpty()) n else null; names += s; if (s != null && t < 8) given[t] = true } }
                val binds = ArrayList<Pair<Int, String>>()
                dp.optJSONArray("bind")?.let { ba -> for (t in 0 until ba.length()) ba.optJSONObject(t)?.let { val ix = it.optInt("index"); binds += ix to it.optString("name"); if (ix in 0..7) given[ix] = true } }
                val combos = JSONObject(); val values = JSONObject()
                mp.optJSONObject("combos")?.let { for (c in it.keys()) combos.put(c, it.opt(c)) }
                inst.optJSONObject("combos")?.let { for (c in it.keys()) combos.put(c, it.opt(c)) }
                mp.optJSONObject("constantshadervalues")?.let { for (c in it.keys()) values.put(c, it.opt(c)) }
                inst.optJSONObject("constantshadervalues")?.let { for (c in it.keys()) values.put(c, it.opt(c)) }
                val prog = program(mp.getString("shader"), combos, given)
                if (prog.us.any { it.name.startsWith("g_AudioSpectrum") }) wantsAudio = true
                passes += Pass(prog, values, names, binds, dp.optString("target").ifEmpty { null })
            }
            l.fx += Fx(fbos, passes)
    }

    // ---------- 设 uniform ----------
    private fun bind(prog: Prog, c: Ctx) {
        GLES30.glUseProgram(prog.p)
        val white = textures["util/white"]!!
        for (u in prog.us) {
            val n = u.name
            val slot = SLOT.matchEntire(n)
            if (slot != null) {
                val i = slot.groupValues[1].toInt(); val t = c.tex.getOrNull(i) ?: white
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id); GLES30.glUniform1i(u.loc, i); continue
            }
            val res = SLOT_RES.matchEntire(n)
            if (res != null) { val t = c.tex.getOrNull(res.groupValues[1].toInt()) ?: white; GLES30.glUniform4f(u.loc, t.w.toFloat(), t.h.toFloat(), t.w.toFloat(), t.h.toFloat()); continue }
            if (n == "g_ModelViewProjectionMatrix") { GLES30.glUniformMatrix4fv(u.loc, 1, false, c.mvp ?: IDENTITY, 0); continue }
            if (n == "g_Time") { GLES30.glUniform1f(u.loc, time); continue }
            val au = AUDIO.matchEntire(n)
            if (au != null) { val arr = audio[au.groupValues[1].toInt()]!![if (au.groupValues[2] == "Left") 0 else 1]; GLES30.glUniform1fv(u.loc, arr.size, arr, 0); continue }
            var v: FloatArray? = c.extra?.get(n)
            if (v == null && u.ann != null) {
                val m = u.ann.optString("material")
                v = nums(if (m.isNotEmpty() && c.values != null && c.values.has(m)) c.values.opt(m) else u.ann.opt("default"))
            }
            if (v == null || v.isEmpty()) v = when (n) { "g_Color" -> floatArrayOf(1f, 1f, 1f); "g_Alpha", "g_Brightness" -> floatArrayOf(1f); else -> floatArrayOf(0f, 0f, 0f, 0f) }
            val vv = v
            fun g(i: Int) = vv[min(i, vv.size - 1)]
            when (u.type) {
                GLES30.GL_FLOAT -> GLES30.glUniform1f(u.loc, g(0))
                GLES30.GL_FLOAT_VEC2 -> GLES30.glUniform2f(u.loc, g(0), g(1))
                GLES30.GL_FLOAT_VEC3 -> GLES30.glUniform3f(u.loc, g(0), g(1), g(2))
                GLES30.GL_FLOAT_VEC4 -> GLES30.glUniform4f(u.loc, g(0), g(1), g(2), if (vv.size > 3) g(3) else 1f)
                GLES30.GL_INT, GLES30.GL_BOOL -> GLES30.glUniform1i(u.loc, g(0).toInt())
            }
        }
    }

    // ---------- 文字层 ----------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 }
    private fun drawText(l: Layer) {
        val s = texts?.value(l.id) ?: l.fixed
        if (s == l.str) return
        l.str = s
        val lines = s.split("\n")
        paint.textSize = l.px * q
        val lh = l.px * 1.2f
        val tw = max(1f, lines.maxOf { paint.measureText(it) } / q)
        val w = tw + l.pad * 2; val h = lines.size * lh + l.pad * 2
        val bw = max(2, ceil(w * q).toInt()).coerceAtMost(4096); val bh = max(2, ceil(h * q).toInt()).coerceAtMost(4096)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        paint.textAlign = when (l.align) { "left" -> Paint.Align.LEFT; "right" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
        val x = when (l.align) { "left" -> l.pad * q; "right" -> (w - l.pad) * q; else -> w * q / 2 }
        val fm = paint.fontMetrics
        lines.forEachIndexed { i, t -> cv.drawText(t, x, (l.pad + lh * (i + .5f)) * q - (fm.ascent + fm.descent) / 2, paint) }
        val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh); bmp.recycle()
        val out = ByteArray(bw * bh * 4); var d = 0
        for (p in px) { out[d] = -1; out[d + 1] = -1; out[d + 2] = -1; out[d + 3] = (p ushr 24).toByte(); d += 4 } // 字只留在透明度里，边缘不发黑
        val t = l.tex!!
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id); GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bw, bh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(out))
        t.w = bw; t.h = bh
        if (l.size[0] != w || l.size[1] != h) { l.size = floatArrayOf(w, h); drop(l.a); drop(l.b); l.named.values.forEach { drop(it) }; l.a = null; l.b = null; l.named.clear() }
    }
    private fun drop(t: Tex?) {
        if (t == null) return
        if (t.fb != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(t.fb), 0)
        GLES30.glDeleteTextures(1, intArrayOf(t.id), 0); all.remove(t)
    }

    // ---------- 画 ----------
    private fun into(t: Tex) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fb); GLES30.glViewport(0, 0, t.w, t.h) }
    private fun fullPass(target: Tex, prog: Prog, c: Ctx) {
        into(target); GLES30.glDisable(GLES30.GL_BLEND); bind(prog, c)
        GLES30.glBindVertexArray(full); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    private fun toScene(l: Layer, t: Tex) {
        into(sceneBuf); GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val c = cos(l.angle); val s = sin(l.angle); val sx = l.size[0] * l.scale[0]; val sy = l.size[1] * l.scale[1]
        val mvp = floatArrayOf(c * sx * 2 / W, s * sx * 2 / H, 0f, 0f, -s * sy * 2 / W, c * sy * 2 / H, 0f, 0f, 0f, 0f, -1f, 0f, l.origin[0] * 2 / W - 1, l.origin[1] * 2 / H - 1, 0f, 1f)
        bind(generic, Ctx(arrayOf(t), mvp = mvp, extra = mapOf("g_Color" to l.color, "g_Alpha" to floatArrayOf(l.alpha), "g_Brightness" to floatArrayOf(l.brightness))))
        GLES30.glBindVertexArray(unit); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
    private fun drawLayer(l: Layer) {
        if (unwrap(l.o.opt("visible")) == false) return
        if (l.kind == "text") drawText(l)
        if (l.fx.isEmpty() && l.kind != "compose") { toScene(l, l.tex!!); return }
        val bw = l.size[0] * q; val bh = l.size[1] * q
        if (l.a == null) { l.a = mkTarget(bw, bh); l.b = mkTarget(bw, bh) }
        var cur = l.a!!; var nxt = l.b!!
        if (l.kind == "compose") { // 取下面已经画好的画面
            into(cur); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glUseProgram(copyProg)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneBuf.id)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProg, "s"), 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(copyProg, "origin"), l.origin[0], l.origin[1])
            GLES30.glUniform2f(GLES30.glGetUniformLocation(copyProg, "size"), l.size[0], l.size[1])
            GLES30.glUniform2f(GLES30.glGetUniformLocation(copyProg, "scale"), l.scale[0], l.scale[1])
            GLES30.glUniform2f(GLES30.glGetUniformLocation(copyProg, "scene"), W, H)
            GLES30.glBindVertexArray(full); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        } else fullPass(cur, generic, Ctx(arrayOf(l.tex)))
        for (fx in l.fx) for (p in fx.passes) {
            fun named(n: String): Tex = l.named.getOrPut(n) { val sc = fx.fbos[n] ?: 1f; mkTarget(bw / sc, bh / sc) }
            val tex = arrayOfNulls<Tex>(8); tex[0] = cur
            p.texNames.forEachIndexed { i, n -> if (n != null && i < 8) tex[i] = if (n.startsWith("_rt_imageLayerComposite")) cur else (texture(n) ?: if (fx.fbos.containsKey(n)) named(n) else null) }
            for ((ix, n) in p.binds) if (ix in 0..7) tex[ix] = if (n == "previous") cur else named(n)
            val target = if (p.target != null) named(p.target) else nxt
            fullPass(target, p.prog, Ctx(tex, values = p.values))
            if (p.target == null) { val x = cur; cur = nxt; nxt = x }
        }
        toScene(l, cur)
    }

    /**
     * 画一帧到当前的默认帧缓冲。[t] 秒；[sw]×[sh] 屏幕像素；[view] = 缩放、中心 x、中心 y（占整张场景的比例，左上为原点）；
     * [bands] 64 段频谱（0–1），没有就传 null。
     */
    fun frame(t: Float, sw: Int, sh: Int, view: FloatArray, bands: FloatArray?) {
        time = t
        val a64 = audio[64]!!
        for (c in 0..1) for (i in 0 until 64) { val target = bands?.getOrNull(i) ?: 0f; a64[c][i] += (target.coerceIn(0f, 1f) - a64[c][i]) * .35f }
        for (n in intArrayOf(32, 16)) { val k = 64 / n; val an = audio[n]!!; for (c in 0..1) for (i in 0 until n) { var s = 0f; for (j in 0 until k) s += a64[c][i * k + j]; an[c][i] = s / k } }

        into(sceneBuf); GLES30.glClearColor(clear[0], clear.getOrElse(1) { 0f }, clear.getOrElse(2) { 0f }, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        for (l in layers) {
            if (l.bad) continue
            try { drawLayer(l) } catch (e: Throwable) { l.bad = true; skipped += l.o.optString("name").ifEmpty { l.id } + "：" + why(e) }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, sw, sh); GLES30.glDisable(GLES30.GL_BLEND)
        val r = rect(view, sw.toFloat() / max(1, sh), aspect)
        GLES30.glUseProgram(blitProg); GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneBuf.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProg, "s"), 0)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(blitProg, "rect"), r[0], 1f - r[1] - r[3], r[2], r[3]) // 缓冲里 y 朝上
        GLES30.glBindVertexArray(full); GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        for (t in all) { if (t.fb != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(t.fb), 0); GLES30.glDeleteTextures(1, intArrayOf(t.id), 0) }
        all.clear(); for (p in cache.values) GLES30.glDeleteProgram(p.p); cache.clear()
    }

    companion object {
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        private val INCLUDE = Regex("^[ \\t]*#include\\s+\"([^\"]+)\".*$", RegexOption.MULTILINE)
        private val COMBO = Regex("//\\s*\\[COMBO\\]\\s*(\\{.*\\})")
        private val UNIFORM = Regex("uniform\\s+(\\w+)\\s+(\\w+)\\s*(?:\\[\\d+\\])?\\s*;\\s*//\\s*(\\{.*\\})")
        private val DEFINE = Regex("#define\\s+(\\w+)")
        private val IFLINE = Regex("^[ \\t]*#\\s*(?:if|elif)\\b(.*)$", RegexOption.MULTILINE)
        private val IDENT = Regex("[A-Za-z_]\\w*")
        private val IDENT_ONLY = Regex("[A-Za-z_]\\w*")
        private val F_SUFFIX1 = Regex("(\\d+\\.\\d*|\\.\\d+)f\\b")
        private val F_SUFFIX2 = Regex("\\b(\\d+)f\\b")
        private val TEX_TO_FLOAT = Regex("(\\bfloat\\s+\\w+\\s*=\\s*)(texSample2D\\s*\\((?:[^()]|\\([^()]*\\))*\\))\\s*;")
        private val SLOT = Regex("g_Texture(\\d)")
        private val SLOT_RES = Regex("g_Texture(\\d)Resolution")
        private val AUDIO = Regex("g_AudioSpectrum(16|32|64)(Left|Right)")

        /** 取景框（占整张场景的比例，左上角为原点）：先按「铺满裁切」，再按缩放收小，中心夹在画面内。 */
        fun rect(view: FloatArray, ca: Float, sa: Float): FloatArray {
            var fw = 1f; var fh = 1f
            if (ca < sa) fw = ca / sa else fh = sa / ca
            val z = max(1f, view.getOrElse(0) { 1f }); fw /= z; fh /= z
            val x = view.getOrElse(1) { .5f }; val y = view.getOrElse(2) { .5f }
            return floatArrayOf((x - fw / 2).coerceIn(0f, 1f - fw), (y - fh / 2).coerceIn(0f, 1f - fh), fw, fh)
        }

        private const val HEAD = """#version 300 es
precision highp float; precision highp int; precision highp sampler2D;
#define GLSL 1
#define texSample2D texture
#define texSample2DLod textureLod
#define frac fract
#define lerp mix
#define atan2 atan
#define saturate(x) clamp(x,0.0,1.0)
#define mul(a,b) ((b)*(a))
#define CAST2(x) vec2(x)
#define CAST3(x) vec3(x)
#define CAST4(x) vec4(x)
#define CAST3X3(x) mat3(x)
#define ddx dFdx
#define ddy dFdy
#define fmod(x,y) ((x)-(y)*trunc((x)/(y)))
#define log10(x) (log(x)*0.4342944819)
"""
        private const val VHEAD = HEAD + "#define attribute in\n#define varying out\n"
        private const val FHEAD = HEAD + "#define varying in\nout vec4 we_FragColor;\n#define gl_FragColor we_FragColor\n"

        /** 包里没有、引擎自带的头文件 */
        private val INCLUDES = mapOf(
            "common.h" to """
#define M_PI 3.14159265359
#define M_PI_HALF 1.57079632679
#define M_PI_2 6.28318530718
#define SQRT_2 1.41421356237
#define SQRT_3 1.73205080756
vec2 rotateVec2(vec2 v, float r){ vec2 cs = vec2(cos(r), sin(r)); return vec2(v.x*cs.x - v.y*cs.y, v.x*cs.y + v.y*cs.x); }
float greyscale(vec3 c){ return dot(c, vec3(0.11, 0.59, 0.3)); }
vec3 rgb2hsv(vec3 c){ vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0); vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g)); vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r)); float d = q.x - min(q.w, q.y); float e = 1.0e-10; return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x); }
vec3 hsv2rgb(vec3 c){ vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0); vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www); return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y); }
""",
            // 混合模式按 WE 编辑器的顺序：0 正常 1 变暗 2 正片叠底 3 颜色加深 4 线性加深 5 变亮 6 滤色 7 颜色减淡 8 线性减淡 9 叠加 10 柔光 11 强光 16 差值 17 排除 18 减去 22 平均
            "common_blending.h" to """
vec3 weBurn(vec3 A, vec3 B){ return mix(max(1.0 - (1.0 - A) / max(B, vec3(1e-4)), 0.0), vec3(0.0), step(B, vec3(1e-4))); }
vec3 weDodge(vec3 A, vec3 B){ return mix(min(A / max(1.0 - B, vec3(1e-4)), 1.0), vec3(1.0), step(vec3(1.0 - 1e-4), B)); }
vec3 ApplyBlending(const int mode, vec3 A, vec3 B, float o){
  vec3 r = B;
  if (mode == 1) r = min(A, B); else if (mode == 2) r = A * B; else if (mode == 3) r = weBurn(A, B); else if (mode == 4) r = max(A + B - 1.0, 0.0);
  else if (mode == 5) r = max(A, B); else if (mode == 6) r = 1.0 - (1.0 - A) * (1.0 - B); else if (mode == 7) r = weDodge(A, B); else if (mode == 8) r = min(A + B, 1.0);
  else if (mode == 9) r = mix(2.0 * A * B, 1.0 - 2.0 * (1.0 - A) * (1.0 - B), step(0.5, A));
  else if (mode == 10) r = mix(2.0 * A * B + A * A * (1.0 - 2.0 * B), sqrt(A) * (2.0 * B - 1.0) + 2.0 * A * (1.0 - B), step(0.5, B));
  else if (mode == 11) r = mix(2.0 * A * B, 1.0 - 2.0 * (1.0 - A) * (1.0 - B), step(0.5, B));
  else if (mode == 16) r = abs(A - B); else if (mode == 17) r = A + B - 2.0 * A * B; else if (mode == 18) r = max(A - B, 0.0);
  else if (mode == 22) r = (A + B) * 0.5;
  return mix(A, r, o);
}
""",
            "common_perspective.h" to """
mat3 squareToQuad(vec2 p0, vec2 p1, vec2 p2, vec2 p3) {
  float dx1 = p1.x - p2.x, dy1 = p1.y - p2.y, dx2 = p3.x - p2.x, dy2 = p3.y - p2.y;
  float sx = p0.x - p1.x + p2.x - p3.x, sy = p0.y - p1.y + p2.y - p3.y;
  float den = dx1 * dy2 - dx2 * dy1;
  float g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
  return mat3(p1.x - p0.x + g * p1.x, p1.y - p0.y + g * p1.y, g,
              p3.x - p0.x + h * p3.x, p3.y - p0.y + h * p3.y, h,
              p0.x, p0.y, 1.0);
}
""",
            "common_blur.h" to """
vec4 weBlurTap(vec2 uv) { vec4 c = texSample2D(g_Texture0, uv); return vec4(c.rgb * c.a, c.a); }
vec4 weBlurEnd(vec4 s) { return vec4(s.rgb / max(s.a, 1e-5), s.a); }
vec4 blur13a(vec2 uv, vec2 d) {
  const float w0 = 0.1592, w1 = 0.1473, w2 = 0.1165, w3 = 0.0789, w4 = 0.0457, w5 = 0.0227, w6 = 0.0096;
  vec4 s = weBlurTap(uv) * w0;
  s += (weBlurTap(uv + d) + weBlurTap(uv - d)) * w1; s += (weBlurTap(uv + d * 2.0) + weBlurTap(uv - d * 2.0)) * w2;
  s += (weBlurTap(uv + d * 3.0) + weBlurTap(uv - d * 3.0)) * w3; s += (weBlurTap(uv + d * 4.0) + weBlurTap(uv - d * 4.0)) * w4;
  s += (weBlurTap(uv + d * 5.0) + weBlurTap(uv - d * 5.0)) * w5; s += (weBlurTap(uv + d * 6.0) + weBlurTap(uv - d * 6.0)) * w6;
  return weBlurEnd(s / (w0 + 2.0 * (w1 + w2 + w3 + w4 + w5 + w6)));
}
vec4 blur7a(vec2 uv, vec2 d) {
  const float w0 = 0.2660, w1 = 0.2130, w2 = 0.1093, w3 = 0.0360;
  vec4 s = weBlurTap(uv) * w0;
  s += (weBlurTap(uv + d) + weBlurTap(uv - d)) * w1; s += (weBlurTap(uv + d * 2.0) + weBlurTap(uv - d * 2.0)) * w2; s += (weBlurTap(uv + d * 3.0) + weBlurTap(uv - d * 3.0)) * w3;
  return weBlurEnd(s / (w0 + 2.0 * (w1 + w2 + w3)));
}
vec4 blur3a(vec2 uv, vec2 d) { return weBlurEnd(weBlurTap(uv) * 0.5 + (weBlurTap(uv + d) + weBlurTap(uv - d)) * 0.25); }
vec3 blur13(vec2 uv, vec2 d) { return blur13a(uv, d).rgb; }
vec3 blur7(vec2 uv, vec2 d) { return blur7a(uv, d).rgb; }
vec3 blur3(vec2 uv, vec2 d) { return blur3a(uv, d).rgb; }
""",
            "common_composite.h" to """
#ifndef COMPOSITE
#define COMPOSITE 0
#endif
#ifndef COMPOSITEMONO
#define COMPOSITEMONO 0
#endif
#ifndef BLENDMODE
#define BLENDMODE 0
#endif
vec2 ApplyCompositeOffset(vec2 c, vec2 r){ return c; }
vec4 ApplyComposite(vec4 A, vec4 B){
#if COMPOSITEMONO
  B.rgb = vec3(dot(B.rgb, vec3(0.299, 0.587, 0.114)));
#endif
#if COMPOSITE == 2
  return vec4(mix(B.rgb, A.rgb, A.a), max(A.a, B.a));
#elif COMPOSITE == 3
  return vec4(B.rgb, B.a * A.a);
#else
  return B;
#endif
}
""",
        )

        /** 包里没有、引擎自带的着色器 */
        private val BUILTIN = mapOf(
            "shaders/genericimage2.vert" to """
uniform mat4 g_ModelViewProjectionMatrix;
attribute vec3 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord;
void main(){ gl_Position = mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix); v_TexCoord = a_TexCoord; }
""",
            "shaders/genericimage2.frag" to """
varying vec2 v_TexCoord; uniform sampler2D g_Texture0;
uniform vec3 g_Color; uniform float g_Alpha; uniform float g_Brightness;
void main(){ vec4 c = texSample2D(g_Texture0, v_TexCoord); gl_FragColor = vec4(c.rgb * g_Color * g_Brightness, c.a * g_Alpha); }
""",
        )
        private const val RAW_V = """#version 300 es
in vec3 a_Position; in vec2 a_TexCoord; out vec2 uv;
void main(){ gl_Position = vec4(a_Position, 1.0); uv = a_TexCoord; }
"""
        private const val COPY_F = """#version 300 es
precision highp float; uniform sampler2D s; uniform vec2 origin, size, scale, scene; in vec2 uv; out vec4 o;
void main(){ vec2 p = origin + vec2(uv.x - 0.5, 0.5 - uv.y) * size * scale; o = texture(s, p / scene); }
"""
        private const val BLIT_F = """#version 300 es
precision highp float; uniform sampler2D s; uniform vec4 rect; in vec2 uv; out vec4 o;
void main(){ o = vec4(texture(s, rect.xy + uv * rect.zw).rgb, 1.0); }
"""
    }
}

/** 文字层的脚本：GL 线程登记，主线程在一个断了网的 WebView 里跑，GL 线程每帧来取最新的字。 */
internal interface WeTexts {
    fun register(id: String, script: String, props: String, initial: String)
    fun value(id: String): String?
}
