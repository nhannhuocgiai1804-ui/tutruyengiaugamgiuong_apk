package com.terabox.reader.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Port từ app.py: _TEMP_DIR, _DISK_CACHE, _CHAPTER_CACHE
 * Quản lý bộ nhớ đệm tạm thời của ứng dụng, không lưu ra thư mục bên ngoài của người dùng.
 */
class AppCacheManager(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "terabox_reader_cache").apply { mkdirs() }
    private val chapterCache = ConcurrentHashMap<Long, List<ReaderChapter>>()

    fun getTempFile(fsId: Long, ext: String): File {
        return File(cacheDir, "$fsId.$ext")
    }

    fun getCachedChapters(fsId: Long): List<ReaderChapter>? {
        return chapterCache[fsId]
    }

    fun setCachedChapters(fsId: Long, chapters: List<ReaderChapter>) {
        chapterCache[fsId] = chapters
    }

    // Tự động kiểm tra và dọn dẹp cache nếu vượt quá giới hạn maxMb (LRU)
    fun trimCache(maxMb: Int = 500) {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        val maxBytes = maxMb.toLong() * 1024 * 1024

        if (totalSize > maxBytes) {
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                totalSize -= f.length()
                f.delete()
                if (totalSize <= maxBytes) break
            }
        }
    }

    fun clearAllCache(): Long {
        chapterCache.clear()
        val files = cacheDir.listFiles() ?: return 0L
        val freed = files.sumOf { it.length() }
        for (f in files) {
            f.delete()
        }
        return freed
    }

    fun getCacheSize(): Long {
        val files = cacheDir.listFiles() ?: return 0L
        return files.sumOf { it.length() }
    }
}
