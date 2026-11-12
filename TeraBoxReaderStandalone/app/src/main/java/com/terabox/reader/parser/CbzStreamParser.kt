package com.terabox.reader.parser

import com.terabox.reader.data.ReaderChapter
import com.terabox.reader.network.RemoteStreamer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Port từ app.py: parse_cbz_remote
 * Đọc CBZ trực tiếp từ TeraBox URL, chỉ tải ảnh khi người dùng cuộn đến trang đó
 */
object CbzStreamParser {

    private val IMG_EXTS = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp")

    suspend fun parseRemote(
        client: OkHttpClient,
        dlink: String,
        fileSize: Long
    ): Result<Pair<RemoteStreamer.RemoteZipFile, List<ReaderChapter>>> = withContext(Dispatchers.IO) {
        try {
            val rz = RemoteStreamer.RemoteZipFile(client, dlink, fileSize)
            val names = rz.namelist().sorted()

            val pages = mutableListOf<ReaderChapter>()
            for (name in names) {
                val ext = if (name.contains(".")) "." + name.substringAfterLast(".").lowercase() else ""
                if (ext in IMG_EXTS && !name.startsWith("__MACOSX")) {
                    pages.add(
                        ReaderChapter(
                            title = name.substringAfterLast("/"),
                            html = null,
                            src = name // Lưu tên entry trong zip để load theo yêu cầu
                        )
                    )
                }
            }

            if (pages.isEmpty()) {
                return@withContext Result.failure(Exception("CBZ không chứa tệp ảnh nào."))
            }

            return@withContext Result.success(Pair(rz, pages))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
