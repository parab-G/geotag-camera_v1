package com.parabg.geotagcamera

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object PdfGenerator {
    fun create(
        context: Context,
        records: List<MediaRecord>,
        title: String,
        perPage: Int,
        landscape: Boolean,
        includeDetails: Boolean,
        includeMaps: Boolean,
        projectLabel: String,
        companyName: String = ""
    ): File {
        val photos = records.filter { it.type == "PHOTO" }
        require(photos.isNotEmpty()) { "Select at least one photograph" }
        require(perPage in 1..10) { "Photographs per page must be between 1 and 10" }
        val pageWidth = if (landscape) 842 else 595
        val pageHeight = if (landscape) 595 else 842
        val pdf = PdfDocument()
        val totalPages = ceil(photos.size.toDouble() / perPage).toInt()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 42, 60); textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(78, 91, 105); textSize = 8.5f }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(203, 211, 219); strokeWidth = .65f }
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
        try {
            for (pageNo in 0 until totalPages) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val margin = 25f
                canvas.drawText(title.take(78), margin, 29f, titlePaint)
                var headerY = 44f
                if (companyName.isNotBlank()) {
                    canvas.drawText(companyName.take(100), margin, headerY, subPaint)
                    headerY += 12f
                }
                if (projectLabel.isNotBlank()) {
                    canvas.drawText("Projects: ${projectLabel.take(130)}", margin, headerY, subPaint)
                    headerY += 11f
                }
                val date = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(java.util.Date())
                canvas.drawText("Generated $date • ${photos.size} photographs", margin, headerY, subPaint)
                val contentTop = headerY + 10f
                canvas.drawLine(margin, contentTop, pageWidth - margin, contentTop, linePaint)
                val chunk = photos.drop(pageNo * perPage).take(perPage)
                val columns = when {
                    perPage == 1 -> 1
                    perPage <= 2 -> 1
                    perPage <= 4 -> 2
                    else -> 3
                }
                val rows = ceil(chunk.size.toDouble() / columns).toInt()
                val footerTop = pageHeight - 28f
                val contentBottom = footerTop - 7f
                val gap = 8f
                val cellWidth = (pageWidth - margin * 2 - (columns - 1) * gap) / columns
                val cellHeight = (contentBottom - contentTop - (rows - 1) * gap) / rows
                chunk.forEachIndexed { index, record ->
                    val col = index % columns
                    val row = index / columns
                    val left = margin + col * (cellWidth + gap)
                    val top = contentTop + row * (cellHeight + gap)
                    val cell = RectF(left, top, left + cellWidth, top + cellHeight)
                    canvas.drawRoundRect(cell, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 247, 249) })
                    val image = loadPhoto(context, record)
                    if (image == null) {
                        canvas.drawText("Photograph unavailable", left + 12f, top + 24f, subPaint)
                    } else {
                        val imageArea = RectF(left + 2f, top + 2f, left + cellWidth - 2f, top + cellHeight - 2f)
                        drawFit(canvas, image, imageArea, imagePaint)
                        if (includeDetails) drawGeotagOverlay(context, canvas, record, imageArea, prefs, includeMaps)
                        image.recycle()
                    }
                }
                canvas.drawLine(margin, footerTop - 3f, pageWidth - margin, footerTop - 3f, linePaint)
                drawBrandMark(canvas, margin, footerTop + 1f, 17f)
                subPaint.textSize = 8f
                canvas.drawText("Generated using GeoTag Worksite Camera", margin + 23f, footerTop + 13f, subPaint)
                val pageText = "Page ${pageNo + 1} of $totalPages"
                canvas.drawText(pageText, pageWidth - margin - subPaint.measureText(pageText), footerTop + 13f, subPaint)
                pdf.finishPage(page)
            }
            val output = File(context.cacheDir, "GeoTag_Report_${System.currentTimeMillis()}.pdf")
            FileOutputStream(output).use { pdf.writeTo(it) }
            return output
        } finally {
            pdf.close()
        }
    }

    private fun loadPhoto(context: Context, record: MediaRecord): Bitmap? {
        if (record.type != "PHOTO") return null
        return try {
            val bitmap = context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it) } ?: return null
            val exif = try { context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { ExifInterface(it) } } catch (_: Exception) { null }
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            val corrected = if (!matrix.isIdentity) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
            if (corrected !== bitmap) bitmap.recycle()
            corrected
        } catch (_: Exception) { null }
    }

    private fun drawGeotagOverlay(
        context: Context, canvas: Canvas, record: MediaRecord, imageArea: RectF,
        prefs: android.content.SharedPreferences, includeMaps: Boolean
    ) {
        val base = min(imageArea.width(), imageArea.height()).coerceAtLeast(1f)
        val pad = (base * .018f).coerceIn(5f, 12f)
        val fontSize = (base * .024f).coerceIn(7f, 13f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = fontSize * prefs.getInt("watermark_text_scale", 100).coerceIn(75, 140) / 100f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val smallLines = mutableListOf<String>()
        if (prefs.getBoolean("watermark_date", true)) smallLines.add(record.capturedAt.replace("T", " ").take(30) + " " + record.timeZone)
        if (prefs.getBoolean("watermark_gps", true)) {
            smallLines.add(if (record.latitude != null && record.longitude != null) AppStore.fmtLocation(record) else "Location unavailable")
        }
        if (prefs.getBoolean("watermark_accuracy", true) && record.accuracy != null) smallLines.add("Accuracy ±${record.accuracy!!.toInt()} m")
        if (prefs.getBoolean("watermark_address", true) && record.address.isNotBlank()) smallLines.add(record.address)
        if (prefs.getBoolean("watermark_compass", true) && record.heading != null) smallLines.add("Heading: ${record.heading!!.toInt()}°")
        if (prefs.getBoolean("watermark_project", true) && record.project.isNotBlank()) smallLines.add(record.project)
        if (prefs.getBoolean("watermark_caption", true) && record.caption.isNotBlank()) smallLines.add(record.caption)
        if (smallLines.isEmpty()) smallLines.add("GeoTag Worksite Camera")
        val map = if (includeMaps && prefs.getBoolean("watermark_map", true) && record.latitude != null && record.longitude != null)
            AppStore.mapTile(context, record.latitude!!, record.longitude!!) else null
        val mapSize = if (map != null) (base * .19f).coerceIn(36f, 68f) else 0f
        val textWidth = imageArea.width() - pad * 3 - mapSize
        val lineHeight = fontSize * 1.25f
        val maxLines = max(1, ((imageArea.height() * .34f - pad * 2) / lineHeight).toInt())
        val lines = smallLines.take(maxLines).map { line ->
            var value = line
            while (textPaint.measureText(value) > textWidth && value.length > 8) value = value.dropLast(2) + "…"
            value
        }
        val panelHeight = max(mapSize, lines.size * lineHeight) + pad * 2
        val panelWidth = min(imageArea.width() - pad * 2, textWidth + mapSize + pad * 3)
        val right = prefs.getBoolean("watermark_right", false)
        val left = if (right) imageArea.right - panelWidth - pad else imageArea.left + pad
        val top = imageArea.bottom - panelHeight - pad
        val panel = RectF(left, top, left + panelWidth, top + panelHeight)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = prefs.getInt("watermark_alpha", 80).coerceIn(35, 95) * 255 / 100 }
        canvas.drawRoundRect(panel, 7f, 7f, background)
        var textX = left + pad
        if (map != null) {
            val mx = if (right) panel.right - mapSize - pad else left + pad
            val mapRect = RectF(mx, top + pad, mx + mapSize, top + pad + mapSize)
            canvas.drawBitmap(map, null, mapRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 5f }
            canvas.drawText("© OSM", mapRect.left + 2, mapRect.bottom - 2, attribution)
            map.recycle()
            textX = if (right) left + pad else left + mapSize + pad * 2
        }
        lines.forEachIndexed { index, value ->
            canvas.drawText(value, textX, top + pad + (index + 1) * lineHeight - fontSize * .12f, textPaint)
        }
        if (record.heading != null) {
            val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = fontSize; typeface = Typeface.DEFAULT_BOLD }
            val symbol = "↑ ${record.heading!!.toInt()}°"
            canvas.drawText(symbol, if (right) panel.left + pad else panel.right - compassPaint.measureText(symbol) - pad, panel.top - 2f, compassPaint)
        }
    }

    private fun drawBrandMark(canvas: Canvas, x: Float, y: Float, size: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 38, 59) }
        canvas.drawRoundRect(RectF(x, y, x + size, y + size), 3f, 3f, bg)
        val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 194, 71) }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 53, 69) }
        canvas.drawArc(RectF(x + size*.18f, y + size*.08f, x + size*.82f, y + size*.61f), 180f, 180f, true, yellow)
        canvas.drawRoundRect(RectF(x + size*.14f, y + size*.43f, x + size*.86f, y + size*.85f), 2f, 2f, white)
        canvas.drawCircle(x + size*.5f, y + size*.64f, size*.13f, bg)
        canvas.drawCircle(x + size*.5f, y + size*.64f, size*.085f, red)
    }

    private fun drawFit(canvas: Canvas, bitmap: Bitmap, target: RectF, paint: Paint) {
        val scale = min(target.width() / bitmap.width, target.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val destination = RectF(
            target.centerX() - width / 2, target.centerY() - height / 2,
            target.centerX() + width / 2, target.centerY() + height / 2
        )
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}
