package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Port từ app.py: parse_docx
 * Phân tích tệp DOCX (unzip word/document.xml) và chia thành các chương
 */
object DocxParser {

    fun parse(file: File): Result<List<ReaderChapter>> {
        try {
            val zip = ZipFile(file)
            val entry = zip.getEntry("word/document.xml") ?: return Result.failure(Exception("DOCX không có word/document.xml"))
            val xmlContent = zip.getInputStream(entry).bufferedReader().readText()
            zip.close()

            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            val chapters = mutableListOf<ReaderChapter>()
            var currentTitle = "Mở đầu"
            val currentHtml = StringBuilder()
            val currentPara = StringBuilder()
            var inHeading = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName == "p") {
                            currentPara.clear()
                            inHeading = false
                        } else if (tagName == "pStyle") {
                            val v = parser.getAttributeValue(null, "val") ?: ""
                            if (v.startsWith("Heading", ignoreCase = true) || v.contains("Title", ignoreCase = true)) {
                                inHeading = true
                            }
                        } else if (tagName == "t") {
                            parser.next()
                            currentPara.append(parser.text ?: "")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "p") {
                            val text = currentPara.toString().trim()
                            if (text.isNotEmpty()) {
                                if (inHeading) {
                                    if (currentHtml.isNotEmpty()) {
                                        chapters.add(ReaderChapter(currentTitle, currentHtml.toString(), null))
                                        currentHtml.clear()
                                    }
                                    currentTitle = text.take(80)
                                    currentHtml.append("<h2>").append(text).append("</h2>")
                                } else {
                                    currentHtml.append("<p>").append(text).append("</p>")
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
