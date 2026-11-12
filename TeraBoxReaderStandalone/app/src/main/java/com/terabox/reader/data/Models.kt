package com.terabox.reader.data

data class TeraBoxFile(
    val fsId: Long,
    val name: String,
    val path: String,
    val size: Long,
    val isDir: Boolean
) {
    fun getExtension(): String {
        return if (name.contains(".")) name.substringAfterLast(".").lowercase() else ""
    }

    fun isReadable(): Boolean {
        val ext = getExtension()
        return ext in listOf("epub", "pdf", "cbz", "cbr", "mobi", "azw", "azw3", "fb2", "txt", "doc", "docx")
    }

    fun formatSize(): String {
        if (size <= 0) return "—"
        if (size > 1e9) return String.format("%.2f GB", size / 1e9)
        if (size > 1e6) return String.format("%.1f MB", size / 1e6)
        return String.format("%d KB", size / 1000)
    }
}

data class ReaderChapter(
    val title: String,
    val html: String?,
    val src: String?
)

data class ReadHistoryItem(
    val fsId: Long,
    val name: String,
    val path: String,
    val url: String,
    val lastRead: Long,
    val progress: Int,
    val total: Int
)

data class SavedLink(
    val url: String,
    val label: String,
    val savedAt: Long
)

data class ReaderSettings(
    val fontFamily: String = "Georgia, serif",
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val textColor: String = "#e8e6e3",
    val bgColor: String = "#141518",
    val pageMode: String = "scroll", // scroll | single | double
    val theme: String = "dark",
    val maxWidth: Int = 820,
    val volumeKeyFlip: Boolean = true,
    val keepScreenOn: Boolean = true,
    val lockOrientation: Boolean = false,
    val maxCacheMb: Int = 500
)
