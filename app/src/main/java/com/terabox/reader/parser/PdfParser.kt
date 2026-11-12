package com.terabox.reader.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.terabox.reader.data.ReaderChapter
import java.io.File

/**
 * Port từ app.py: parse_pdf
 * Sử dụng Android Native PdfRenderer để render từng trang ảnh manga hoặc văn bản
 */
object PdfParser {

    fun parse(file: File): Result<List<ReaderChapter>> {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            val chapters = mutableListOf<ReaderChapter>()
            for (i in 0 until pageCount) {
                chapters.add(
                    ReaderChapter(
                        title = "Trang ${i + 1}",
                        html = null,
                        src = "page_$i" // Chỉ số trang để render khi cuộn đến
                    )
                )
            }
            renderer.close()
            pfd.close()

            return Result.success(chapters)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Render một trang PDF cụ thể thành Bitmap trong bộ nhớ (lazy rendering)
     */
    fun renderPage(file: File, pageIndex: Int, scale: Float = 1.5f): Bitmap? {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                renderer.close()
                pfd.close()
                return null
            }
            val page = renderer.openPage(pageIndex)
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()
            return bitmap
        } catch (_: Exception) {
            return null
        }
    }
}
