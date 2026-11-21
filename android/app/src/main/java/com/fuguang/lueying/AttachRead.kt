package com.fuguang.lueying

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** 把你发来的文件读成她能看的：文字类直接读，Word / PPT / Excel 从包里抠字，PDF 画成几页图。 */
object AttachRead {
    private val TEXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "ini", "conf", "yaml", "yml", "toml", "srt", "ass", "lrc",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php", "sh", "bat", "sql", "css", "gradle", "properties",
    )

    /** 读不出字返回 null。 */
    fun text(f: File): String? = runCatching {
        val ext = f.extension.lowercase()
        when {
            ext in TEXT -> f.inputStream().use { head(it, 8_000_000) }.let { decode(it) }
            ext == "docx" -> office(f) { it == "word/document.xml" }
            ext == "pptx" -> office(f) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            ext == "xlsx" -> office(f) { it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml")) }
            ext == "epub" -> office(f) { it.endsWith(".xhtml") || it.endsWith(".html") }
            else -> f.inputStream().use { head(it, 8_000_000) }.takeIf { looksText(it) }?.let { decode(it) }
        }
    }.getOrNull()

    /** 最多读 n 个字节（InputStream.readNBytes 要 Android 13）。 */
    private fun head(i: java.io.InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16_384); var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size, left)); if (r <= 0) break; out.write(buf, 0, r); left -= r }
        return out.toByteArray()
    }

    /**
     * 认编码（照浏览器 / ICU 的做法）：先看 BOM；能严格按 UTF-8 解开就是 UTF-8；
     * 否则交给系统自带的 ICU 编码检测（android.icu.text.CharsetDetector，按中文提示），认不出就按 GB18030（GBK 的超集）。
     * 以前只在 UTF-8 / GBK 之间二选一，UTF-16、Big5 和夹了几个坏字节的 GBK 会读成乱码。
     */
    private fun decode(b: ByteArray): String {
        fun cut(n: Int) = b.copyOfRange(n, b.size)
        when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> return String(cut(3), Charsets.UTF_8)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> return String(cut(2), Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> return String(cut(2), Charsets.UTF_16BE)
        }
        if (strictUtf8(b)) return String(b, Charsets.UTF_8)
        val name = runCatching {
            val d = android.icu.text.CharsetDetector()
            d.setDeclaredEncoding("GB18030")
            d.setText(if (b.size > 256_000) b.copyOf(256_000) else b)
            // 中文书里西文单字节编码多半是误判，跳过
            d.detectAll().firstOrNull { m -> m.confidence >= 30 && !m.name.startsWith("ISO-8859") && !m.name.startsWith("windows-125") && runCatching { charset(m.name) }.isSuccess }?.name
        }.getOrNull()
        return String(b, charset(name ?: "GB18030"))
    }

    /** 整段都是合法的 UTF-8（末尾被截断的半个字不算错）。 */
    private fun strictUtf8(b: ByteArray): Boolean {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val body = if (b.size > 4) b.copyOf(b.size - trailing(b)) else b
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(body)) }.isSuccess
    }

    /** 末尾不完整的 UTF-8 字节数（读文件只读了前 8MB，可能切在一个字中间）。 */
    private fun trailing(b: ByteArray): Int {
        for (k in 1..3) {
            val x = b[b.size - k].toInt() and 0xFF
            if (x and 0xC0 == 0xC0) return if (k < (if (x >= 0xF0) 4 else if (x >= 0xE0) 3 else 2)) k else 0
            if (x and 0xC0 != 0x80) return 0
        }
        return 0
    }

    private fun looksText(b: ByteArray): Boolean = b.isNotEmpty() && (
        (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))) ||
            b.take(4000).count { it == 0.toByte() } == 0
        )

    /** Office 文件是 zip：按顺序取出要的 xml，去掉标签，段落、单元格之间换行。 */
    private fun office(f: File, want: (String) -> Boolean): String = ZipFile(f).use { z ->
        val names = z.entries().asSequence().map { it.name }.filter(want).sortedWith(compareBy({ it.length }, { it })).toList()
        names.joinToString("\n\n") { n ->
            val xml = z.getInputStream(z.getEntry(n)).use { head(it, 3_000_000) }.toString(Charsets.UTF_8)
            xml.replace(Regex("</(w:p|a:p|p|div|h\\d|li|tr|si|row)>"), "\n")
                .replace(Regex("</(c|td|w:tc)>"), "\t")
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }

    /** PDF 的前几页画成图（给能看图、但不收 PDF 的供应商）。 */
    fun pdfPages(f: File, max: Int): List<ByteArray> = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(max, r.pageCount)).map { i ->
                    r.openPage(i).use { pg ->
                        val k = 1400f / maxOf(pg.width, pg.height)
                        val bmp = Bitmap.createBitmap((pg.width * k).toInt().coerceAtLeast(1), (pg.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}
