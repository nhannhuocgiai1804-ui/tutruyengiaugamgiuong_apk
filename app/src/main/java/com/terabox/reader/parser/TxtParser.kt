package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Port từ app.py: parse_txt
 * Hỗ trợ nhận diện chương: Chương, Chapter, Quyển, Hồi, Tập...
 */
object TxtParser {

    private val CHAPTER_REGEX = Pattern.compile(
        "^((?:chương|chapter|quyển|hồi|phần|tập|volume|ch\\.?)\\s*\\d+[^\\n]*)",
        Pattern.CASE_INSENSITIVE or Pattern.MULTILINE
    )

    fun parse(file: File): Result<List<ReaderChapter>> {
        try {
            val bytes = file.readBytes()
            var text: String? = null

            for (enc in listOf(Charsets.UTF_8, Charset.forName("windows-1258"), Charset.forName("windows-1252"))) {
                try {
                    text = String(bytes, enc)
                    break
                } catch (_: Exception) {}
            }
            val content = text ?: String(bytes, Charsets.UTF_8)

            val matcher = CHAPTER_REGEX.matcher(content)
            val chapters = mutableListOf<ReaderChapter>()
            var lastIndex = 0
            var currentTitle = "Mở đầu"

            while (matcher.find()) {
                val matchStart = matcher.start()
                if (matchStart > lastIndex) {
                    val body = content.substring(lastIndex, matchStart).trim()
                    if (body.isNotEmpty()) {
                        val html = "<p>" + body.replace("\n+", "</p><p>") + "</p>"
                        chapters.add(ReaderChapter(currentTitle, html, null))
                    }
                }
                currentTitle = matcher.group(1)?.trim() ?: "Chương mới"
                lastIndex = matcher.end()
            }

            if (lastIndex < content.length) {
                val remaining = content.substring(lastIndex).trim()
                if (remaining.isNotEmpty()) {
                    val html = "<p>" + remaining.replace("\n+", "</p><p>") + "</p>"
                    chapters.add(ReaderChapter(currentTitle, html, null))
                }
            }

            // Nếu không tìm thấy chương nào -> chia thành từng phần 3000 ký tự giống app.py
            if (chapters.isEmpty()) {
                val chunkSize = 3000
                var i = 0
                while (i < content.length) {
                    val end = minOf(content.length, i + chunkSize)
                    val seg = content.substring(i, end).trim()
                    val html = "<p>" + seg.replace("\n+", "</p><p>") + "</p>"
                    chapters.add(ReaderChapter("Phần ${(i / chunkSize) + 1}", html, null))
                    i += chunkSize
                }
            }

            return Result.success(chapters)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
