package com.parabg.geotagcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

data class MediaRecord(
    val id: String,
    var uri: String,
    var name: String,
    var type: String,
    var capturedAt: String,
    var timeZone: String,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var accuracy: Float? = null,
    var caption: String = "",
    var project: String = "",
    var address: String = "",
    var locationStatus: String = if (latitude != null && longitude != null) "AVAILABLE" else "UNAVAILABLE",
    var heading: Float? = null
)

object AppStore {
    private fun metadataDir(context: Context) = File(context.filesDir, "metadata").apply { mkdirs() }
    private fun projectsFile(context: Context) = File(context.filesDir, "projects.json")
    private fun trashDir(context: Context) = File(context.filesDir, "trash").apply { mkdirs() }

    fun save(context: Context, record: MediaRecord) {
        val j = JSONObject().apply {
            put("id", record.id); put("uri", record.uri); put("name", record.name)
            put("type", record.type); put("capturedAt", record.capturedAt); put("timeZone", record.timeZone)
            put("latitude", record.latitude ?: JSONObject.NULL); put("longitude", record.longitude ?: JSONObject.NULL)
            put("accuracy", record.accuracy ?: JSONObject.NULL); put("caption", record.caption); put("project", record.project)
            put("address", record.address); put("locationStatus", record.locationStatus)
            put("heading", record.heading ?: JSONObject.NULL)
        }
        File(metadataDir(context), "${record.id}.json").writeText(j.toString(2))
    }

    fun all(context: Context): MutableList<MediaRecord> {
        val list = mutableListOf<MediaRecord>()
        metadataDir(context).listFiles { f -> f.extension.equals("json", true) }?.forEach { file ->
            try {
                val j = JSONObject(file.readText())
                fun nullableDouble(k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k) else null
                fun nullableFloat(k: String): Float? =
                    if (j.has(k) && !j.isNull(k)) j.optDouble(k).toFloat()
                    else if (k == "accuracy" && j.has("accuracyMeters") && !j.isNull("accuracyMeters")) j.optDouble("accuracyMeters").toFloat()
                    else null
                list.add(MediaRecord(
                    id = j.optString("id", file.nameWithoutExtension),
                    uri = j.optString("uri"),
                    name = j.optString("name", j.optString("displayName", "Media")),
                    type = j.optString("type", "PHOTO"),
                    capturedAt = j.optString("capturedAt"),
                    timeZone = j.optString("timeZone"),
                    latitude = nullableDouble("latitude"), longitude = nullableDouble("longitude"),
                    accuracy = nullableFloat("accuracy"), caption = j.optString("caption"),
                    project = j.optString("project").takeUnless { it == "null" } ?: "",
                    address = j.optString("address"),
                    locationStatus = j.optString("locationStatus", if (nullableDouble("latitude") != null) "AVAILABLE" else "UNAVAILABLE"),
                    heading = if (j.has("heading") && !j.isNull("heading")) j.optDouble("heading").toFloat() else null
                ))
            } catch (_: Exception) { }
        }
        return list.sortedByDescending { it.capturedAt }.toMutableList()
    }

    fun find(context: Context, id: String) = all(context).firstOrNull { it.id == id }

    fun delete(context: Context, record: MediaRecord) {
        try { context.contentResolver.delete(Uri.parse(record.uri), null, null) } catch (_: Exception) { }
        File(metadataDir(context), "${record.id}.json").delete()
    }

    fun projects(context: Context): MutableList<String> = try {
        val a = JSONArray(projectsFile(context).readText())
        MutableList(a.length()) { i -> a.optString(i) }.filter { it.isNotBlank() }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun saveProjects(context: Context, values: List<String>) {
        val array = JSONArray()
        values.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }
        projectsFile(context).writeText(array.toString(2))
    }


    /** Soft-delete keeps the original public media item intact and moves only its app record to Trash. */
    fun moveToTrash(context: Context, record: MediaRecord): Boolean {
        return try {
            val source = File(metadataDir(context), "${record.id}.json")
            if (!source.exists()) return false
            val target = File(trashDir(context), "${record.id}.json")
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        } catch (_: Exception) { false }
    }

    fun trashed(context: Context): MutableList<MediaRecord> {
        val result = mutableListOf<MediaRecord>()
        trashDir(context).listFiles { f -> f.extension.equals("json", true) }?.forEach { file ->
            try {
                val j = JSONObject(file.readText())
                result.add(MediaRecord(
                    id = j.optString("id", file.nameWithoutExtension), uri = j.optString("uri"),
                    name = j.optString("name", "Media"), type = j.optString("type", "PHOTO"),
                    capturedAt = j.optString("capturedAt"), timeZone = j.optString("timeZone"),
                    latitude = if (j.isNull("latitude")) null else j.optDouble("latitude"),
                    longitude = if (j.isNull("longitude")) null else j.optDouble("longitude"),
                    accuracy = if (j.isNull("accuracy")) null else j.optDouble("accuracy").toFloat(),
                    caption = j.optString("caption"), project = j.optString("project"),
                    address = j.optString("address"), locationStatus = j.optString("locationStatus", "UNAVAILABLE"),
                    heading = if (j.isNull("heading")) null else j.optDouble("heading").toFloat()
                ))
            } catch (_: Exception) { }
        }
        return result.sortedByDescending { it.capturedAt }.toMutableList()
    }

    fun restoreFromTrash(context: Context, record: MediaRecord): Boolean {
        return try {
            val source = File(trashDir(context), "${record.id}.json")
            if (!source.exists()) return false
            source.copyTo(File(metadataDir(context), "${record.id}.json"), overwrite = true)
            source.delete()
            true
        } catch (_: Exception) { false }
    }

    fun permanentlyDeleteFromTrash(context: Context, record: MediaRecord) {
        try { context.contentResolver.delete(Uri.parse(record.uri), null, null) } catch (_: Exception) { }
        File(trashDir(context), "${record.id}.json").delete()
    }

    /** Copy an original photo/video into a second, user-visible phone Gallery location. */
    fun copyOriginalToGallery(context: Context, record: MediaRecord): Uri? {
        return try {
            val isPhoto = record.type == "PHOTO"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Original_${record.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, if (isPhoto) "image/jpeg" else "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    val folder = if (isPhoto) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/GeoTag Worksite Camera/Original Copies")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val target = context.contentResolver.insert(collection, values) ?: return null
            try {
                context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { input ->
                    context.contentResolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
                        ?: error("Cannot open destination")
                } ?: error("Cannot read original media")
                if (Build.VERSION.SDK_INT >= 29) {
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    context.contentResolver.update(target, ready, null, null)
                }
                target
            } catch (e: Exception) {
                context.contentResolver.delete(target, null, null)
                throw e
            }
        } catch (_: Exception) { null }
    }

    fun fmtLocation(r: MediaRecord): String =
        if (r.latitude != null && r.longitude != null)
            "Lat: ${"%.6f".format(Locale.US, r.latitude)}  Lon: ${"%.6f".format(Locale.US, r.longitude)}"
        else "GPS unavailable"

    fun mapTile(context: Context, lat: Double, lon: Double, zoom: Int = 15): Bitmap? {
        val n = 1 shl zoom
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return mapTileXY(context, zoom, x, y)
    }

    private fun mapTileXY(context: Context, zoom: Int, x: Int, y: Int): Bitmap? {
        return try {
            val n = 1 shl zoom
            val tileX = ((x % n) + n) % n
            val tileY = y.coerceIn(0, n - 1)
            val cache = File(File(context.cacheDir, "maptiles"), "$zoom-$tileX-$tileY.png")
            if (cache.exists()) return BitmapFactory.decodeFile(cache.absolutePath)
            val connection = (URL("https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "GeoTagWorksiteCamera/1.0 Android")
            }
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
            connection.disconnect()
            if (bitmap != null) {
                cache.parentFile?.mkdirs()
                cache.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
            bitmap
        } catch (_: Exception) { null }
    }

    fun mapOverview(context: Context, records: List<MediaRecord>, requestedSize: Int = 768): Bitmap {
        val valid = records.filter { it.latitude != null && it.longitude != null }
        if (valid.isEmpty()) return mapPlaceholder(requestedSize)
        val size = requestedSize
        val centerLat = valid.map { it.latitude!! }.average()
        val centerLon = valid.map { it.longitude!! }.average()
        var chosenZoom = 15
        var centerTileX = 0.0
        var centerTileY = 0.0
        for (z in 15 downTo 4) {
            val n = 1 shl z
            val xs = valid.map { (it.longitude!! + 180.0) / 360.0 * n }
            val ys = valid.map {
                val lat = Math.toRadians(it.latitude!!.coerceIn(-85.0, 85.0))
                (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * n
            }
            if ((xs.maxOrNull()!! - xs.minOrNull()!!) * 256 < size * .82 &&
                (ys.maxOrNull()!! - ys.minOrNull()!!) * 256 < size * .82) {
                chosenZoom = z
                break
            }
        }
        val n = 1 shl chosenZoom
        val lat = Math.toRadians(centerLat.coerceIn(-85.0, 85.0))
        centerTileX = (centerLon + 180.0) / 360.0 * n
        centerTileY = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * n
        val centerX = floor(centerTileX).toInt()
        val centerY = floor(centerTileY).toInt()
        val tileSize = size / 3
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(226, 233, 226))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (dy in -1..1) for (dx in -1..1) {
            val tile = mapTileXY(context, chosenZoom, centerX + dx, centerY + dy)
            if (tile != null) {
                val l = (dx + 1) * tileSize.toFloat()
                val t = (dy + 1) * tileSize.toFloat()
                canvas.drawBitmap(tile, null, RectF(l, t, l + tileSize, t + tileSize), paint)
                tile.recycle()
            }
        }
        valid.forEachIndexed { index, record ->
            val pLat = Math.toRadians(record.latitude!!.coerceIn(-85.0, 85.0))
            val x = ((record.longitude!! + 180.0) / 360.0 * n - centerX + 1.0) * tileSize
            val y = ((1.0 - ln(tan(pLat) + 1.0 / cos(pLat)) / Math.PI) / 2.0 * n - centerY + 1.0) * tileSize
            if (x in 0.0..size.toDouble() && y in 0.0..size.toDouble()) {
                paint.color = Color.WHITE; canvas.drawCircle(x.toFloat(), y.toFloat(), size * .027f, paint)
                paint.color = Color.rgb(220, 52, 52); canvas.drawCircle(x.toFloat(), y.toFloat(), size * .022f, paint)
                paint.color = Color.WHITE; paint.textSize = size * .021f; paint.typeface = Typeface.DEFAULT_BOLD
                val label = (index + 1).toString()
                canvas.drawText(label, x.toFloat() - paint.measureText(label) / 2, y.toFloat() + paint.textSize / 3, paint)
            }
        }
        paint.color = Color.WHITE; paint.textSize = size * .023f; paint.setShadowLayer(2f, 1f, 1f, Color.BLACK)
        canvas.drawText("© OpenStreetMap contributors", 10f, size - 14f, paint)
        return bitmap
    }

    fun mapPlaceholder(size: Int = 160): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(220, 229, 222))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 204, 193); strokeWidth = size * .018f }
        for (i in 0..5) {
            c.drawLine(i * size / 5f, 0f, i * size / 5f, size.toFloat(), p)
            c.drawLine(0f, i * size / 5f, size.toFloat(), i * size / 5f, p)
        }
        p.color = Color.WHITE; p.strokeWidth = size * .08f; p.style = Paint.Style.STROKE
        c.drawLine(size*.12f, size*.75f, size*.88f, size*.25f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(220, 52, 52)
        c.drawCircle(size*.52f, size*.48f, size*.09f, p)
        p.color = Color.WHITE; c.drawCircle(size*.52f, size*.48f, size*.035f, p)
        return b
    }

    fun watermarkedPhoto(context: Context, record: MediaRecord): Uri? {
        if (record.type != "PHOTO") return null
        val original = context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it) } ?: return null
        val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
        val right = prefs.getBoolean("watermark_right", false)
        val tileBitmap = if (prefs.getBoolean("watermark_map", true) && record.latitude != null && record.longitude != null) mapTile(context, record.latitude!!, record.longitude!!) else null
        val showMap = tileBitmap != null
        val base = min(bitmap.width, bitmap.height)
        val pad = (base * .025f).coerceAtLeast(12f)
        val mapSize = (base * .19f).coerceIn(72f, 180f)
        val lines = mutableListOf<String>()
        if (prefs.getBoolean("watermark_date", true)) lines.add(record.capturedAt.replace("T", " ").take(32))
        if (prefs.getBoolean("watermark_gps", true)) lines.add(fmtLocation(record))
        if (prefs.getBoolean("watermark_accuracy", true) && record.accuracy != null) lines.add("Accuracy: ±${record.accuracy!!.toInt()} m")
        if (prefs.getBoolean("watermark_project", true) && record.project.isNotBlank()) lines.add(record.project)
        if (prefs.getBoolean("watermark_caption", true) && record.caption.isNotBlank()) lines.add(record.caption)
        if (lines.isEmpty()) lines.add("GeoTag Worksite Camera")
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = (base * .026f).coerceIn(18f, 30f)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val lineHeight = textPaint.textSize * 1.3f
        val maxLine = lines.maxOf { textPaint.measureText(it) }
        val blockWidth = (maxLine + if (showMap) mapSize + pad else 0f + pad * 2).coerceAtMost(bitmap.width * .94f)
        val blockHeight = max(mapSize.takeIf { showMap } ?: 0f, lines.size * lineHeight) + pad
        val left = if (right) bitmap.width - blockWidth - pad else pad
        val top = bitmap.height - blockHeight - pad
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 118 }
        canvas.drawRoundRect(left, top, left + blockWidth, top + blockHeight, 14f, 14f, bg)
        var textX = left + pad
        if (showMap) {
            val map = tileBitmap!!
            val mapLeft = if (right) left + blockWidth - mapSize - pad else left + pad
            val dst = RectF(mapLeft, top + pad / 2, mapLeft + mapSize, top + pad / 2 + mapSize)
            canvas.drawBitmap(map, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
            val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 9f; setShadowLayer(2f, 1f, 1f, Color.BLACK) }
            canvas.drawText("© OSM", dst.left + 3, dst.bottom - 4, attribution)
            map.recycle()
            textX = if (right) left + pad else left + mapSize + pad * 1.5f
        }
        val maxText = (if (showMap) blockWidth - mapSize - pad * 2 else blockWidth - pad * 2).coerceAtLeast(50f)
        lines.forEachIndexed { i, line ->
            val width = textPaint.measureText(line)
            val originalSize = textPaint.textSize
            if (width > maxText) textPaint.textSize = (originalSize * maxText / width).coerceAtLeast(11f)
            val y = top + pad + (i + 1) * lineHeight - textPaint.textSize * .15f
            canvas.drawText(line, textX, y, textPaint)
            textPaint.textSize = originalSize
        }
        val name = "GTC_WM_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GeoTag Worksite Camera/Exports")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            uri
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null); null
        } finally { bitmap.recycle(); original.recycle() }
    }
}
