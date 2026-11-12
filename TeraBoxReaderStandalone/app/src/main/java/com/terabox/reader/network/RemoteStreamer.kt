package com.terabox.reader.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Port toàn bộ cơ chế Remote Streaming đọc file ZIP/EPUB/CBZ trực tiếp qua HTTP Range
 * - _supports_range
 * - _range_read
 * - _fetch_tail
 * - RemoteZipFile: đọc Central Directory ở cuối file (tail 65KB), sau đó chỉ tải đúng file/ảnh cần thiết.
 */
object RemoteStreamer {

    private const val EOCD_SIG = 0x06054b50 // PK\x05\x06
    private const val CD_SIG = 0x02014b50   // PK\x01\x02
    private const val LFH_SIG = 0x04034b50  // PK\x03\x04
    private const val ZIP64_LOC_SIG = 0x07064b50 // PK\x06\x07

    // Port từ app.py: _supports_range(sess, dlink)
    suspend fun supportsRange(client: OkHttpClient, dlink: String): Pair<Boolean, Long?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(dlink)
                .head()
                .header("Referer", "https://dm.terabox.com/")
                .header("User-Agent", TeraBoxSession.UA)
                .build()
            val resp = client.newCall(req).execute()
            val accept = resp.header("Accept-Ranges", "").lowercase()
            val cl = resp.header("Content-Length")?.toLongOrNull()
            val ok = (accept == "bytes" || resp.code == 200) && cl != null && cl > 0
            return@withContext Pair(ok, cl)
        } catch (_: Exception) {
            return@withContext Pair(false, null)
        }
    }

    // Port từ app.py: _range_read(sess, dlink, start, length)
    suspend fun rangeRead(client: OkHttpClient, dlink: String, start: Long, length: Long): ByteArray = withContext(Dispatchers.IO) {
        val end = start + length - 1
        val req = Request.Builder()
            .url(dlink)
            .header("Referer", "https://dm.terabox.com/")
            .header("User-Agent", TeraBoxSession.UA)
            .header("Range", "bytes=$start-$end")
            .build()

        val resp = client.newCall(req).execute()
        if (resp.code != 200 && resp.code != 206) {
            throw IOException("Range fetch HTTP ${resp.code}")
        }
        return@withContext resp.body?.bytes() ?: ByteArray(0)
    }

    data class ZipEntryInfo(
        val lhdrOffset: Long,
        val compSize: Long,
        val uncompSize: Long,
        val method: Int
    )

    /**
     * Port lớp RemoteZipFile từ app.py
     * Đọc ZIP từ remote URL bằng HTTP Range, không tải toàn bộ file về đĩa
     */
    class RemoteZipFile(
        private val client: OkHttpClient,
        private val dlink: String,
        private val fileSize: Long
    ) {
        private var cdMap: MutableMap<String, ZipEntryInfo>? = null

        suspend fun loadCd() = withContext(Dispatchers.IO) {
            if (cdMap != null) return@withContext

            val tailSize = minOf(65536L, fileSize)
            val tailStart = fileSize - tailSize
            val tail = rangeRead(client, dlink, tailStart, tailSize)

            // Tìm EOCD signature PK từ cuối về
            var eocdOff = -1
            for (i in (tail.size - 22) downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    eocdOff = i
                    break
                }
            }
            if (eocdOff < 0) {
                throw IOException("Không tìm thấy EOCD trong file ZIP remote.")
            }

            val buf = ByteBuffer.wrap(tail, eocdOff, tail.size - eocdOff).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(buf.position() + 12)
            var cdSize = buf.int.toLong() and 0xFFFFFFFFL
            var cdOffset = buf.int.toLong() and 0xFFFFFFFFL

            // Hỗ trợ ZIP64 fallback
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
                val locOff = eocdOff - 20
                if (locOff >= 0) {
                    val locBuf = ByteBuffer.wrap(tail, locOff, 20).order(ByteOrder.LITTLE_ENDIAN)
                    if (locBuf.int == ZIP64_LOC_SIG) {
                        locBuf.position(locBuf.position() + 4)
                        val z64EocdOff = locBuf.long
                        val z64Data = rangeRead(client, dlink, z64EocdOff, 56)
                        val z64Buf = ByteBuffer.wrap(z64Data).order(ByteOrder.LITTLE_ENDIAN)
                        z64Buf.position(40)
                        cdSize = z64Buf.long
                        cdOffset = z64Buf.long
                    }
                }
            }

            // Tải Central Directory qua HTTP Range
            val cdData = rangeRead(client, dlink, cdOffset, cdSize)
            val cdBuf = ByteBuffer.wrap(cdData).order(ByteOrder.LITTLE_ENDIAN)
            val map = mutableMapOf<String, ZipEntryInfo>()

            while (cdBuf.remaining() >= 46) {
                val sig = cdBuf.int
                if (sig != CD_SIG) break

                cdBuf.position(cdBuf.position() + 6)
                val method = cdBuf.short.toInt() and 0xFFFF
                cdBuf.position(cdBuf.position() + 8)
                var compSize = cdBuf.int.toLong() and 0xFFFFFFFFL
                var uncompSize = cdBuf.int.toLong() and 0xFFFFFFFFL
                val fnameLen = cdBuf.short.toInt() and 0xFFFF
                val extraLen = cdBuf.short.toInt() and 0xFFFF
                val commentLen = cdBuf.short.toInt() and 0xFFFF
                cdBuf.position(cdBuf.position() + 8)
                var lhdrOffset = cdBuf.int.toLong() and 0xFFFFFFFFL

                val nameBytes = ByteArray(fnameLen)
                cdBuf.get(nameBytes)
                val fname = String(nameBytes, Charsets.UTF_8)

                if (extraLen > 0) {
                    val extraBytes = ByteArray(extraLen)
                    cdBuf.get(extraBytes)
                    // Parse ZIP64 extra field nếu kích thước vượt 4GB
                    if (compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL || lhdrOffset == 0xFFFFFFFFL) {
                        val eBuf = ByteBuffer.wrap(extraBytes).order(ByteOrder.LITTLE_ENDIAN)
                        while (eBuf.remaining() >= 4) {
                            val tag = eBuf.short.toInt() and 0xFFFF
                            val sz = eBuf.short.toInt() and 0xFFFF
                            if (tag == 0x0001 && eBuf.remaining() >= sz) {
                                if (uncompSize == 0xFFFFFFFFL) uncompSize = eBuf.long
                                if (compSize == 0xFFFFFFFFL) compSize = eBuf.long
                                if (lhdrOffset == 0xFFFFFFFFL) lhdrOffset = eBuf.long
                                break
                            } else {
                                eBuf.position(eBuf.position() + sz)
                            }
                        }
                    }
                }

                if (commentLen > 0) {
                    cdBuf.position(cdBuf.position() + commentLen)
                }

                map[fname] = ZipEntryInfo(lhdrOffset, compSize, uncompSize, method)
            }

            cdMap = map
        }

        suspend fun namelist(): List<String> {
            loadCd()
            return cdMap?.keys?.toList() ?: emptyList()
        }

        suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
            loadCd()
            val info = cdMap?.get(name) ?: throw IOException("Không tìm thấy entry '$name' trong file ZIP")

            // Đọc Local File Header (30 bytes)
            val lhdr = rangeRead(client, dlink, info.lhdrOffset, 30)
            val lBuf = ByteBuffer.wrap(lhdr).order(ByteOrder.LITTLE_ENDIAN)
            val sig = lBuf.int
            if (sig != LFH_SIG) throw IOException("Sai signature Local File Header")

            lBuf.position(26)
            val fnameLen = lBuf.short.toInt() and 0xFFFF
            val extraLen = lBuf.short.toInt() and 0xFFFF
            val dataOffset = info.lhdrOffset + 30 + fnameLen + extraLen

            val rawData = rangeRead(client, dlink, dataOffset, info.compSize)

            if (info.method == 0) {
                // Stored (không nén)
                return@withContext rawData
            } else if (info.method == 8) {
                // Deflated -> Giải nén bằng Inflater(true)
                val inflater = Inflater(true)
                inflater.setInput(rawData)
                val out = ByteArrayOutputStream(info.uncompSize.toInt().coerceAtLeast(1024))
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) out.write(buffer, 0, count)
                    if (count == 0 && inflater.needsInput()) break
                }
                inflater.end()
                return@withContext out.toByteArray()
            } else {
                throw IOException("Phương thức nén ${info.method} chưa được hỗ trợ.")
            }
        }
    }
}
