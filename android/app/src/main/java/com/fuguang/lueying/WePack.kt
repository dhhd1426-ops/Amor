package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Wallpaper Engine 壁纸包（第 548 版）。手机版导出的 .mpkg（PKGM0014）和电脑的 scene.pkg（PKGVxxxx）结构一样：
 * 长度 + 版本名，文件数，逐条（名字长度、名字、偏移、长度），偏移从表头结束处算起。不整体读进内存，要哪个读哪个。
 * 格式实测见项目文档《壁纸-mpkg格式》。
 */
internal class WePack(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val at = HashMap<String, LongArray>()
    val version: String

    init {
        val head = ByteArray(4); raf.readFully(head)
        val n = le32(head, 0)
        if (n < 4 || n > 32) throw IllegalArgumentException("不是 Wallpaper Engine 的壁纸包")
        val v = ByteArray(n); raf.readFully(v); version = String(v, Charsets.UTF_8)
        if (!version.startsWith("PKGM") && !version.startsWith("PKGV")) throw IllegalArgumentException("不是 Wallpaper Engine 的壁纸包")
        raf.readFully(head); val count = le32(head, 0)
        if (count < 0 || count > 20000) throw IllegalArgumentException("壁纸包坏了")
        val list = ArrayList<Triple<String, Long, Long>>(count)
        val two = ByteArray(8)
        repeat(count) {
            raf.readFully(head); val l = le32(head, 0)
            if (l < 0 || l > 4096) throw IllegalArgumentException("壁纸包坏了")
            val nm = ByteArray(l); raf.readFully(nm); raf.readFully(two)
            list += Triple(String(nm, Charsets.UTF_8), le32(two, 0).toLong() and 0xFFFFFFFFL, le32(two, 4).toLong() and 0xFFFFFFFFL)
        }
        val base = raf.filePointer; val size = raf.length()
        for ((name, off, len) in list) if (base + off + len <= size) at[name] = longArrayOf(base + off, len)
    }

    fun has(name: String) = at.containsKey(name)

    @Synchronized
    fun bytes(name: String): ByteArray? {
        val e = at[name] ?: return null
        if (e[1] > 256L * 1024 * 1024) return null
        val out = ByteArray(e[1].toInt()); raf.seek(e[0]); raf.readFully(out); return out
    }

    fun text(name: String): String? = bytes(name)?.let { String(it, Charsets.UTF_8) }

    override fun close() { runCatching { raf.close() } }

    companion object {
        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /** 只看文件头：是不是壁纸包 */
        fun looksLike(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { r ->
                val b = ByteArray(12); r.readFully(b)
                le32(b, 0) == 8 && (String(b, 4, 4, Charsets.US_ASCII) == "PKGM" || String(b, 4, 4, Charsets.US_ASCII) == "PKGV")
            }
        }.getOrDefault(false)
    }
}

/** 解出来的一张贴图：RGBA 每像素 4 字节（第 0 行是图的顶，没有预乘）；[etc2] 为真时是 ETC2 RGBA8 压缩块，原样交给显卡。 */
internal class WeImage(val w: Int, val h: Int, val data: ByteArray, val etc2: Boolean = false)

internal object WeTex {
    /** LZ4 块解压 */
    fun lz4(src: ByteArray, from: Int, len: Int, n: Int): ByteArray {
        val dst = ByteArray(n); var i = from; var o = 0; val end = from + len
        while (i < end) {
            val t = src[i++].toInt() and 0xFF
            var lit = t ushr 4
            if (lit == 15) { while (true) { val b = src[i++].toInt() and 0xFF; lit += b; if (b != 255) break } }
            if (lit > 0) { System.arraycopy(src, i, dst, o, lit); i += lit; o += lit }
            if (i >= end) break
            val off = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8); i += 2
            var m = t and 15
            if (m == 15) { while (true) { val b = src[i++].toInt() and 0xFF; m += b; if (b != 255) break } }
            m += 4
            var s = o - off
            if (off <= 0 || s < 0) throw IllegalArgumentException("贴图数据坏了")
            if (off >= m) { System.arraycopy(dst, s, dst, o, m); o += m }
            else { repeat(m) { dst[o++] = dst[s++] } } // 重叠拷贝要一个字节一个字节来
        }
        return dst
    }

    /**
     * .tex → RGBA。头：TEXV0005、TEXI0001，然后 7 个 int（格式、标志、贴图宽高、图片宽高、未知），TEXB000x，图数，
     * （3 版起）内嵌图片格式，（4 版）是否视频，mip 数，每级：宽、高、（2 版起）是否 LZ4、解压后大小，数据大小，数据。
     * 格式：0 RGBA8888，8 RG88，9 R8，5 手机版导出的 ETC2 RGBA8。贴图可能被补到 2 的幂，按图片宽高裁回来。
     * 长边超过 [maxSide] 的对半缩（4K 原图一张 33MB，手机上用不着）。
     */
    fun decode(b: ByteArray, maxSide: Int): WeImage {
        if (b.size < 64 || String(b, 0, 4, Charsets.US_ASCII) != "TEXV" || String(b, 9, 4, Charsets.US_ASCII) != "TEXI") throw IllegalArgumentException("不认识的贴图")
        val fmt = WePack.le32(b, 18)
        val imgW = WePack.le32(b, 34); val imgH = WePack.le32(b, 38)
        val tb = String(b, 46, 8, Charsets.US_ASCII)
        val ver = tb.substring(4).toIntOrNull() ?: 1
        var o = 55 + 4
        var embedded = -1
        if (ver >= 3) { embedded = WePack.le32(b, o); o += 4 }
        if (ver >= 4) o += 4
        o += 4 // mip 数：只要第一级
        val w = WePack.le32(b, o); val h = WePack.le32(b, o + 4); o += 8
        var lz = 0; var full = 0
        if (ver >= 2) { lz = WePack.le32(b, o); full = WePack.le32(b, o + 4); o += 8 }
        val size = WePack.le32(b, o); o += 4
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || size < 0 || o + size > b.size) throw IllegalArgumentException("贴图数据坏了")
        val raw: ByteArray; val rawOff: Int
        if (lz != 0) { raw = lz4(b, o, size, full); rawOff = 0 } else { raw = b; rawOff = o }

        if (embedded >= 0) { // 里面是一张普通图片（png / jpg）
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, rawOff, raw.size - rawOff, opt)
            var s = 1; while (opt.outWidth / s > maxSide || opt.outHeight / s > maxSide) s *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, rawOff, raw.size - rawOff, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IllegalArgumentException("贴图里的图片解不出来")
            return fromBitmap(bmp).also { bmp.recycle() }
        }
        if (fmt == 5) { // ETC2 RGBA8：安卓显卡直接认
            val need = ((w + 3) / 4) * ((h + 3) / 4) * 16
            if (raw.size - rawOff < need) throw IllegalArgumentException("贴图数据坏了")
            return WeImage(w, h, if (rawOff == 0 && raw.size == need) raw else raw.copyOfRange(rawOff, rawOff + need), etc2 = true)
        }
        val bpp = when (fmt) { 0 -> 4; 8 -> 2; 9 -> 1; else -> throw IllegalArgumentException("还不支持的贴图格式 $fmt") }
        if (raw.size - rawOff < w * h * bpp) throw IllegalArgumentException("贴图数据坏了")
        val cw = if (imgW in 1..w) imgW else w; val ch = if (imgH in 1..h) imgH else h
        var out = ByteArray(cw * ch * 4)
        for (y in 0 until ch) {
            var si = rawOff + y * w * bpp; var di = y * cw * 4
            when (bpp) {
                4 -> System.arraycopy(raw, si, out, di, cw * 4)
                2 -> repeat(cw) { out[di] = raw[si]; out[di + 1] = raw[si + 1]; out[di + 2] = 0; out[di + 3] = -1; si += 2; di += 4 }
                else -> repeat(cw) { val v = raw[si]; out[di] = v; out[di + 1] = v; out[di + 2] = v; out[di + 3] = -1; si += 1; di += 4 }
            }
        }
        var ow = cw; var oh = ch
        while ((ow > maxSide || oh > maxSide) && ow >= 2 && oh >= 2) { out = half(out, ow, oh); ow /= 2; oh /= 2 }
        return WeImage(ow, oh, out)
    }

    /** 对半缩：2×2 取平均 */
    private fun half(src: ByteArray, w: Int, h: Int): ByteArray {
        val nw = w / 2; val nh = h / 2; val out = ByteArray(nw * nh * 4)
        for (y in 0 until nh) {
            var a = (y * 2) * w * 4; var b = a + w * 4; var d = y * nw * 4
            repeat(nw) {
                for (c in 0 until 4) {
                    val s = (src[a + c].toInt() and 0xFF) + (src[a + 4 + c].toInt() and 0xFF) + (src[b + c].toInt() and 0xFF) + (src[b + 4 + c].toInt() and 0xFF)
                    out[d + c] = ((s + 2) shr 2).toByte()
                }
                a += 8; b += 8; d += 4
            }
        }
        return out
    }

    fun fromBitmap(bmp: Bitmap): WeImage {
        val w = bmp.width; val h = bmp.height; val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4); var d = 0
        for (p in px) { out[d] = (p shr 16).toByte(); out[d + 1] = (p shr 8).toByte(); out[d + 2] = p.toByte(); out[d + 3] = (p ushr 24).toByte(); d += 4 }
        return WeImage(w, h, out)
    }
}
