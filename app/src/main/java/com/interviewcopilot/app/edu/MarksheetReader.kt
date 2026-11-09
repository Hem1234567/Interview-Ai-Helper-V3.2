package com.interviewcopilot.app.edu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.interviewcopilot.app.core.OcrBox
import com.interviewcopilot.app.core.RowBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads marksheets from photos and PDFs with on-device text recognition (ML Kit, works offline), then rebuilds the
 * table rows from the position of each piece of text so a subject stays on the same line as its marks.
 */
class MarksheetReader(private val ctx: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Returns one string per table row, over all the given files (pages are read in order). */
    suspend fun read(uris: List<Uri>): List<String> {
        val rows = ArrayList<String>()
        for (uri in uris) {
            val mime = ctx.contentResolver.getType(uri).orEmpty()
            if (mime.contains("pdf") || uri.toString().lowercase().endsWith(".pdf")) {
                for (page in renderPdf(uri)) {
                    rows.addAll(recognise(InputImage.fromBitmap(page, 0)))
                    page.recycle()
                }
            } else {
                rows.addAll(recognise(InputImage.fromFilePath(ctx, uri)))
            }
        }
        return rows
    }

    private suspend fun recognise(image: InputImage): List<String> = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val boxes = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { OcrBox(line.text, it.left, it.top, it.right, it.bottom) }
                }
                cont.resume(RowBuilder.build(boxes))
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun renderPdf(uri: Uri): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val fd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return out
        fd.use {
            PdfRenderer(it).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, MAX_PDF_PAGES)) {
                    renderer.openPage(i).use { page ->
                        val scale = TARGET_WIDTH.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(TARGET_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_PDF_PAGES = 4
        const val TARGET_WIDTH = 1800
    }
}
