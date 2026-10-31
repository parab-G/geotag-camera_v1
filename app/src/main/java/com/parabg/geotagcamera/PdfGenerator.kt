package com.parabg.geotagcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

object PdfGenerator {
    fun create(context: Context, records: List<MediaRecord>, title: String, perPage: Int, landscape: Boolean,
               includeDetails: Boolean, includeMaps: Boolean, projectLabel: String): File {
        require(records.isNotEmpty()) { "No media selected" }
        require(perPage in 1..10) { "Items per page must be between 1 and 10" }
        val width = if (landscape) 842 else 595
        val height = if (landscape) 595 else 842
        val pdf = PdfDocument()
        val pages = ceil(records.size.toDouble() / perPage).toInt()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 44, 62); textSize = 20f; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 48, 58); textSize = 9f }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 10f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = .7f }
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            for (pageNo in 0 until pages) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val margin = 28f
                canvas.drawText(title.take(90), margin, 34f, titlePaint)
                val date = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.US).format(java.util.Date())
                canvas.drawText("Generated $date • ${records.size} media items", margin, 51f, sub)
                if (projectLabel.isNotBlank()) canvas.drawText("Project: $projectLabel", margin, 66f, sub)
                canvas.drawLine(margin, 76f, width - margin, 76f, line)
                val chunk = records.drop(pageNo * perPage).take(perPage)
                val columns = when { perPage == 1 -> 1; perPage <= 4 -> 2; else -> 3 }
                val rows = ceil(chunk.size.toDouble() / columns).toInt()
                val top = 88f
                val availableHeight = height - top - 30f
                val gap = 9f
                val cellWidth = (width - margin * 2 - (columns - 1) * gap) / columns
                val cellHeight = (availableHeight - (rows - 1) * gap) / rows
                chunk.forEachIndexed { index, record ->
                    val col = index % columns
                    val row = index / columns
                    val left = margin + col * (cellWidth + gap)
                    val y = top + row * (cellHeight + gap)
                    canvas.drawRoundRect(RectF(left, y, left + cellWidth, y + cellHeight), 5f, 5f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 248, 250) })
                    val imageHeight = cellHeight * if (includeDetails) .65f else .9f
                    val image = loadBitmap(context, record)
                    if (image != null) {
                        drawFit(canvas, image, RectF(left + 5, y + 5, left + cellWidth - 5, y + imageHeight), imagePaint)
                        image.recycle()
                    } else {
                        val missing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
                        canvas.drawRect(left + 5, y + 5, left + cellWidth - 5, y + imageHeight, missing)
                        canvas.drawText("Preview unavailable", left + 12, y + imageHeight / 2, body)
                    }
                    if (includeDetails) {
                        val baseline = y + imageHeight + 12f
                        canvas.drawText("${record.type} • ${record.capturedAt.replace("T", " ").take(30)}", left + 7, baseline, body)
                        canvas.drawText(AppStore.fmtLocation(record).take(48), left + 7, baseline + 11f, body)
                        canvas.drawText((record.caption.ifBlank { record.name }).take(48), left + 7, baseline + 22f, body)
                        if (record.project.isNotBlank()) canvas.drawText(record.project.take(42), left + 7, baseline + 33f, body)
                        if (includeMaps && record.latitude != null && record.longitude != null) {
                            val map = AppStore.mapTile(context, record.latitude!!, record.longitude!!) ?: AppStore.mapPlaceholder(96)
                            drawFit(canvas, map, RectF(left + cellWidth - 45, baseline - 4, left + cellWidth - 7, baseline + 34), imagePaint)
                            map.recycle()
                        }
                    }
                }
                canvas.drawLine(margin, height - 21f, width - margin, height - 21f, line)
                canvas.drawText("Generated using GeoTag Worksite Camera • Page ${pageNo + 1} of $pages", margin, height - 8f, sub)
                pdf.finishPage(page)
            }
            val output = File(context.cacheDir, "GeoTag_Report_${System.currentTimeMillis()}.pdf")
            FileOutputStream(output).use { pdf.writeTo(it) }
            return output
        } finally { pdf.close() }
    }

    private fun loadBitmap(context: Context, record: MediaRecord): Bitmap? {
        return try {
            if (record.type == "PHOTO") {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sample = maxOf(1, maxOf(bounds.outWidth / 1400, bounds.outHeight / 1400))
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(record.uri))
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { retriever.release() }
            }
        } catch (_: Exception) { null }
    }

    private fun drawFit(canvas: Canvas, bitmap: Bitmap, target: RectF, paint: Paint) {
        val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val dst = RectF(target.centerX() - w / 2, target.centerY() - h / 2, target.centerX() + w / 2, target.centerY() + h / 2)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}
