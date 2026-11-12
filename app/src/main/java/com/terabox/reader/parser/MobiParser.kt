package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port từ app.py: parse_mobi và _palmdoc_decompress
 * Giải nén định dạng MOBI/AZW/AZW3 bằng thuật toán PalmDOC LZ77 thuần túy
 */
object MobiParser {

    // Port từ app.py: _palmdoc_decompress(data)
    fun palmdocDecompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            if (c == 0) {
                out.write(0)
            } else if (c in 1..8) {
                val end = minOf(data.size, i + c)
                out.write(data, i, end - i)
                i += c
            } else if (c <= 0x7F) {
                out.write(c)
            } else if (c <= 0xBF) {
                if (i >= data.size) break
                val nextC = data[i].toInt() and 0xFF
                i++
                val distance = ((c and 0x3F) shl 8) or nextC
                val length = (distance and 0x07) + 3
                val offset = (distance shr 3).coerceAtLeast(1)

                val outBuf = out.toByteArray()
                for (k in 0 until length) {
                    val pos = outBuf.size - offset + k
                    if (pos >= 0 && pos < outBuf.size) {
                        out.write(outBuf[pos].toInt())
                    } else {
                        out.write(0x20)
                    }
                }
            } else {
                out.write(0x20)
                out.write(c xor 0x80)
            }
        }
        return out.toByteArray()
    }

    fun parse(file: File): Result<List<ReaderChapter>> {
        try {
            val raw = file.readBytes()
            if (raw.size < 78) return Result.failure(Exception("Tệp MOBI quá nhỏ."))

            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            buf.position(76)
            val numRecords = buf.short.toInt() and 0xFFFF
            if (numRecords < 2) return Result.failure(Exception("MOBI không chứa record."))

            val offsets = IntArray(numRecords + 1)
            for (i in 0 until numRecords) {
                offsets[i] = buf.int
                buf.position(buf.position() + 4)
            }
            offsets[numRecords] = raw.size

            val rec0Len = offsets[1] - offsets[0]
            val rec0 = raw.copyOfRange(offsets[0], offsets[1])
            val r0Buf = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)

            val compression = r0Buf.short.toInt() and 0xFFFF
            r0Buf.position(8)
            val textRecords = r0Buf.short.toInt() and 0xFFFF

            val textStream = ByteArrayOutputStream()
            val maxRecs = minOf(1 + textRecords, numRecords)
            for (i in 1 until maxRecs) {
                var chunk = raw.copyOfRange(offsets[i], offsets[i + 1])
                if (compression == 2 && chunk.isNotEmpty()) {
                    val trail = chunk.last().toInt() and 0x03
                    if (trail > 0) {
                        chunk = chunk.copyOfRange(0, chunk.size - trail)
                    }
                    chunk = palmdocDecompress(chunk)
                }
                textStream.write(chunk)
            }

            val fullText = String(textStream.toByteArray(), Charsets.UTF_8)
            val parts = fullText.split(Regex("<(?:mbp:pagebreak|h[1-3])[^>]*>", RegexOption.IGNORE_CASE))

            val chapters = mutableListOf<ReaderChapter>()
            for ((idx, part) in parts.withIndex()) {
                val clean = part.trim()
                if (clean.length > 50) {
                    chapters.add(
                        ReaderChapter(
                            title = "Phần ${idx + 1}",
                            html = clean,
                            src = null
                        )
                    )
                }
            }

            if (chapters.isEmpty()) {
                chapters.add(ReaderChapter("Toàn bộ", fullText, null))
            }

            return Result.success(chapters)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
