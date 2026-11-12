package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import com.terabox.reader.network.RemoteStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLDecoder

/**
 * Port từ app.py: parse_epub_remote
 * Tải trực tiếp qua RemoteZipFile mà không tải trọn vẹn cuốn sách về máy
 */
object EpubStreamParser {

    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val RE_OPF_PATH = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val RE_ITEM = Regex("""<item\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val RE_ITEMREF = Regex("""<itemref\b[^>]*?idref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val RE_BODY = Regex("""<body[^>]*>(.*?)</body>""", OPTS)
    private val RE_NAVPOINT = Regex(
        """<navPoint[^>]*>.*?<text>(.*?)</text>.*?<content[^>]+src\s*=\s*["']([^"'#]+)""",
        OPTS
    )
    private val RE_NAV_LINK = Regex(
        """<a[^>]+href\s*=\s*["']([^"'#]+)[^"']*["'][^>]*>(.*?)</a>""",
        OPTS
    )
    private val RE_TAGS = Regex("""<[^>]+>""")

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1)

    private fun decode(s: String): String =
        try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    suspend fun parseRemote(
        client: OkHttpClient,
        dlink: String,
        fileSize: Long
    ): Result<List<ReaderChapter>> = withContext(Dispatchers.IO) {
        try {
            val rz = RemoteStreamer.RemoteZipFile(client, dlink, fileSize)
            val allNames = rz.namelist().toSet()
            val lowerMap = allNames.associateBy { it.lowercase() }

            // 1. Đọc META-INF/container.xml
            val container = String(rz.read("META-INF/container.xml"), Charsets.UTF_8)
            val opfPath = RE_OPF_PATH.find(container)?.groupValues?.get(1)
                ?: return@withContext Result.failure(Exception("Không tìm thấy OPF path trong container.xml"))
            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            // 2. Đọc tệp OPF
            val opf = String(rz.read(opfPath), Charsets.UTF_8)

            fun resolve(hint: String): String? {
                val dec = decode(hint)
                val clean = (opfDir + dec).replace("\\", "/")
                if (allNames.contains(clean)) return clean
                if (allNames.contains(dec)) return dec
                return lowerMap[clean.lowercase()] ?: lowerMap[dec.lowercase()]
            }

            // Manifest: id -> href. Đồng thời tìm file mục lục (NCX hoặc NAV)
            val manifest = mutableMapOf<String, String>()
            var tocHref: String? = null
            for (m in RE_ITEM.findAll(opf)) {
                val attrs = m.groupValues[1]
                val id = attr(attrs, "id")
                val href = attr(attrs, "href")
                if (id != null && href != null) {
                    manifest[id] = decode(href)
                    val mediaType = attr(attrs, "media-type") ?: ""
                    val props = attr(attrs, "properties") ?: ""
                    if (mediaType.contains("ncx", true) || props.contains("nav", true)) {
                        if (tocHref == null || mediaType.contains("ncx", true)) tocHref = href
                    }
                }
            }

            // Spine: thứ tự các chương
            val spineRefs = RE_ITEMREF.findAll(opf).map { it.groupValues[1] }.toList()

            // Mục lục: tên file -> tiêu đề
            val tocTitles = mutableMapOf<String, String>()
            try {
                val tocEntry = tocHref?.let { resolve(it) }
                if (tocEntry != null) {
                    val toc = String(rz.read(tocEntry), Charsets.UTF_8)
                    if (tocEntry.endsWith(".ncx", true)) {
                        for (m in RE_NAVPOINT.findAll(toc)) {
                            val text = m.groupValues[1].replace(RE_TAGS, "").trim()
                            val src = m.groupValues[2].trim().substringAfterLast("/")
                            if (src.isNotEmpty() && text.isNotEmpty()) tocTitles.putIfAbsent(src, text)
                        }
                    } else {
                        for (m in RE_NAV_LINK.findAll(toc)) {
                            val src = m.groupValues[1].trim().substringAfterLast("/")
                            val text = m.groupValues[2].replace(RE_TAGS, "").trim()
                            if (src.isNotEmpty() && text.isNotEmpty()) tocTitles.putIfAbsent(src, text)
                        }
                    }
                }
            } catch (_: Exception) {}

            // Trích xuất các chương
            val chapters = mutableListOf<ReaderChapter>()
            for (ref in spineRefs) {
                val href = manifest[ref] ?: continue
                val entry = resolve(href) ?: continue
                try {
                    val rawHtml = String(rz.read(entry), Charsets.UTF_8)
                    val content = RE_BODY.find(rawHtml)?.groupValues?.get(1) ?: rawHtml
                    val fname = href.substringAfterLast("/").substringBefore("#")
                    val title = tocTitles[fname] ?: "Chương ${chapters.size + 1}"
                    chapters.add(ReaderChapter(title = title, html = content, src = null))
                } catch (_: Exception) {}
            }

            if (chapters.isEmpty()) {
                return@withContext Result.failure(Exception("EPUB không chứa chương nào đọc được."))
            }
            return@withContext Result.success(chapters)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
