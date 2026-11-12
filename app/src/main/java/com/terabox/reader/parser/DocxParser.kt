package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Port từ app.py: parse_docx
 * Phân tích tệp DOCX (unzip word/document.xml) và chia thành các chương.
 * Lưu ý: parser không bật namespace nên tên thẻ có dạng "w:p", "w:t", "w:pStyle".
 */
object DocxParser {

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun parse(file: File): Result<List<ReaderChapter>> {
        try {
            val xmlContent = ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return Result.failure(Exception("DOCX không có word/document.xml"))
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
            }

            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            val chapters = mutableListOf<ReaderChapter>()
            var currentTitle = "Mở đầu"
            val currentHtml = StringBuilder()
            val currentPara = StringBuilder()
            var inHeading = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = (parser.name ?: "").substringAfterLast(':')
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tagName) {
                            "p" -> {
                                currentPara.setLength(0)
                                inHeading = false
                            }
                            "pStyle" -> {
                                var v = ""
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).substringAfterLast(':') == "val") {
                                        v = parser.getAttributeValue(i) ?: ""
                                    }
                                }
                                if (v.startsWith("Heading", ignoreCase = true) || v.contains("Title", ignoreCase = true)) {
                                    inHeading = true
                                }
                            }
                            "t" -> {
                                if (parser.next() == XmlPullParser.TEXT) {
                                    currentPara.append(parser.text ?: "")
                                }
                                // Sau next(): nếu là TEXT thì thẻ đóng </w:t> sẽ được vòng lặp xử lý tiếp
                                eventType = parser.eventType
                                continue
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "p") {
                            val text = currentPara.toString().trim()
                            if (text.isNotEmpty()) {
                                if (inHeading) {
                                    if (currentHtml.isNotEmpty()) {
                                        chapters.add(ReaderChapter(currentTitle, currentHtml.toString(), null))
                                        currentHtml.setLength(0)
                                    }
                                    currentTitle = text.take(80)
                                    currentHtml.append("<h2>").append(escapeHtml(text)).append("</h2>")
                                } else {
                                    currentHtml.append("<p>").append(escapeHtml(text)).append("</p>")
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (currentHtml.isNotEmpty()) {
                chapters.add(ReaderChapter(currentTitle, currentHtml.toString(), null))
            }

            if (chapters.isEmpty()) {
                return Result.failure(Exception("DOCX không có nội dung văn bản."))
            }

            return Result.success(chapters)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
