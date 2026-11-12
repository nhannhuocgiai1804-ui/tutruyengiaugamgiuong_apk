package com.terabox.reader.parser

import android.util.Base64
import com.terabox.reader.data.ReaderChapter
import com.terabox.reader.network.RemoteStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * Port từ app.py: parse_epub_remote
 * Tải trực tiếp qua RemoteZipFile mà không tải trọn vẹn cuốn sách về máy
 */
object EpubStreamParser {

    suspend fun parseRemote(
        client: OkHttpClient,
        dlink: String,
        fileSize: Long
    ): Result<List<ReaderChapter>> = withContext(Dispatchers.IO) {
        try {
            val rz = RemoteStreamer.RemoteZipFile(client, dlink, fileSize)
            val allNames = rz.namelist().toSet()

            // 1. Đọc META-INF/container.xml
            val containerBytes = rz.read("META-INF/container.xml")
            val container = String(containerBytes, Charsets.UTF_8)

            val opfMatch = Pattern.compile("full-path="([^"]+)"").matcher(container)
            if (!opfMatch.find()) {
                return@withContext Result.failure(Exception("Không tìm thấy OPF path trong container.xml"))
            }
            val opfPath = opfMatch.group(1) ?: ""
            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            // 2. Đọc tệp OPF
            val opfBytes = rz.read(opfPath)
            val opf = String(opfBytes, Charsets.UTF_8)

            // Parse Manifest
            val manifest = mutableMapOf<String, String>()
            val itemMatcher = Pattern.compile("<item\\b([^>]+)/?>", Pattern.CASE_INSENSITIVE).matcher(opf)
            while (itemMatcher.find()) {
                val attrs = itemMatcher.group(1) ?: ""
                val idM = Pattern.compile("\\bid="([^"]+)"", Pattern.CASE_INSENSITIVE).matcher(attrs)
                val hrefM = Pattern.compile("\\bhref="([^"]+)"", Pattern.CASE_INSENSITIVE).matcher(attrs)
                if (idM.find() && hrefM.find()) {
                    manifest[idM.group(1)!!] = URLDecoder.decode(hrefM.group(1)!!, "UTF-8")
                }
            }

            // Parse Spine
            val spineRefs = mutableListOf<String>()
            val spineM = Pattern.compile("idref="([^"]+)"").matcher(opf)
            while (spineM.find()) {
                spineM.group(1)?.let { spineRefs.add(it) }
            }

            fun resolve(hint: String): String? {
                val dec = URLDecoder.decode(hint, "UTF-8")
                val clean = (opfDir + dec).replace("\\", "/")
                if (allNames.contains(clean)) return clean
                if (allNames.contains(dec)) return dec
                val lowerMap = allNames.associateBy { it.lowercase() }
                return lowerMap[clean.lowercase()] ?: lowerMap[dec.lowercase()]
            }

            // Parse Mục lục (TOC từ NCX / NAV)
            val tocTitles = mutableMapOf<String, String>()
            try {
                var ncxHref: String? = null
                val ncxM = Pattern.compile("<item\\b[^>]+media-type="[^"]*(?:ncx|nav)[^"]*"[^>]+href="([^"]+)"", Pattern.CASE_INSENSITIVE).matcher(opf)
                if (ncxM.find()) ncxHref = ncxM.group(1)

                if (ncxHref != null) {
                    val ncxEntry = resolve(ncxHref)
                    if (ncxEntry != null) {
                        val ncx = String(rz.read(ncxEntry), Charsets.UTF_8)
                        val navM = Pattern.compile("<navPoint[^>]*>.*?<text>(.*?)</text>.*?<content[^>]+src="([^"#]+)", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(ncx)
                        while (navM.find()) {
                            val text = navM.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                            val src = navM.group(2)?.trim()?.substringAfterLast("/") ?: ""
                            if (src.isNotEmpty()) tocTitles[src] = text
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
                    val bodyM = Pattern.compile("<body[^>]*>(.*?)</body>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(rawHtml)
                    var content = if (bodyM.find()) bodyM.group(1) ?: rawHtml else rawHtml

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
