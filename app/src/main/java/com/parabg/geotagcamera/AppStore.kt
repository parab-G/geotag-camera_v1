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
    private fun trashMediaDir(context: Context) = File(trashDir(context), "media").apply { mkdirs() }

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
    /**
     * Move an item to Recently Deleted. On Android 10+ mark the app-owned MediaStore row
     * pending so other gallery apps stop showing it. On Android 8/9, keep a private backup
     * before removing the public MediaStore item so restore remains possible.
     */
    fun moveToTrash(context: Context, record: MediaRecord): Boolean {
        val sourceMetadata = File(metadataDir(context), "${record.id}.json")
        if (!sourceMetadata.exists()) return false
        val trashMetadata = File(trashDir(context), "${record.id}.json")
        val mediaUri = Uri.parse(record.uri)
        var legacyBackup: File? = null
        return try {
            sourceMetadata.copyTo(trashMetadata, overwrite = true)
            if (Build.VERSION.SDK_INT >= 29) {
                val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) }
                if (context.contentResolver.update(mediaUri, pending, null, null) <= 0) {
                    trashMetadata.delete()
                    return false
                }
            } else {
                val extension = if (record.type == "PHOTO") "jpg" else "mp4"
                legacyBackup = File(trashMediaDir(context), "${record.id}.$extension")
                context.contentResolver.openInputStream(mediaUri)?.use { input ->
                    legacyBackup.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to preserve the media file")
                if (context.contentResolver.delete(mediaUri, null, null) <= 0) {
                    legacyBackup.delete()
                    trashMetadata.delete()
                    return false
                }
            }
            sourceMetadata.delete()
            true
        } catch (_: Exception) {
            legacyBackup?.delete()
            trashMetadata.delete()
            false
        }
    }

    fun trashed(context: Context): MutableList<MediaRecord> {
        val result = mutableListOf<MediaRecord>()
        trashDir(context).listFiles { f -> f.isFile && f.extension.equals("json", true) }?.forEach { file ->
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
        val source = File(trashDir(context), "${record.id}.json")
        if (!source.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (context.contentResolver.update(Uri.parse(record.uri), ready, null, null) <= 0) return false
            } else {
                val extension = if (record.type == "PHOTO") "jpg" else "mp4"
                val backup = File(trashMediaDir(context), "${record.id}.$extension")
                if (!backup.exists()) return false
                val photo = record.type == "PHOTO"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, record.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (photo) "image/jpeg" else "video/mp4")
                    if (photo) put(MediaStore.Images.Media.DATE_TAKEN, parseCapturedAtMillis(record.capturedAt))
                    if (Build.VERSION.SDK_INT >= 29) {
                        val folder = if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/GeoTag Worksite Camera")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val collection = if (photo) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val restored = context.contentResolver.insert(collection, values) ?: return false
                try {
                    backup.inputStream().use { input ->
                        context.contentResolver.openOutputStream(restored)?.use { output -> input.copyTo(output) }
                            ?: error("Unable to restore media")
                    }
                    record.uri = restored.toString()
                    val existing = MediaStore.MediaColumns.IS_PENDING
                    if (Build.VERSION.SDK_INT >= 29) {
                        val ready = ContentValues().apply { put(existing, 0) }
                        context.contentResolver.update(restored, ready, null, null)
                    }
                    backup.delete()
                } catch (e: Exception) {
                    context.contentResolver.delete(restored, null, null)
                    throw e
                }
            }
            val destination = File(metadataDir(context), "${record.id}.json")
            if (Build.VERSION.SDK_INT < 29) save(context, record)
            else source.copyTo(destination, overwrite = true)
            source.delete()
            true
        } catch (_: Exception) { false }
    }

    fun permanentlyDeleteFromTrash(context: Context, record: MediaRecord) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { context.contentResolver.delete(Uri.parse(record.uri), null, null) } catch (_: Exception) { }
        } else {
            val extension = if (record.type == "PHOTO") "jpg" else "mp4"
            File(trashMediaDir(context), "${record.id}.$extension").delete()
        }
        File(trashDir(context), "${record.id}.json").delete()
    }

    private fun parseCapturedAtMillis(value: String): Long =
        try { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        catch (_: Exception) { System.currentTimeMillis() }

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
        else "Location unavailable"

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
        var hasActualTile = false
        for (dy in -1..1) for (dx in -1..1) {
            val tile = mapTileXY(context, chosenZoom, centerX + dx, centerY + dy)
            if (tile != null) {
                hasActualTile = true
                val l = (dx + 1) * tileSize.toFloat()
                val t = (dy + 1) * tileSize.toFloat()
                canvas.drawBitmap(tile, null, RectF(l, t, l + tileSize, t + tileSize), paint)
                tile.recycle()
            }
        }
        // Only show actual tiles; never use a generic illustration as a location map.
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
        if (hasActualTile) canvas.drawText("© OpenStreetMap contributors", 10f, size - 14f, paint)
        else {
            paint.textSize = size * .035f
            canvas.drawText("Map unavailable offline", size * .08f, size * .52f, paint)
        }
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


    /** Build a transparent full-frame image layer for optional video watermark export. */
    fun videoWatermarkBitmap(context: Context, record: MediaRecord, width: Int, height: Int): Bitmap {
        val w = width.coerceIn(640, 1920)
        val h = height.coerceIn(640, 1920)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
        val base = min(w, h).toFloat()
        val pad = (base * .025f).coerceAtLeast(16f)
        val map = if (prefs.getBoolean("watermark_map", true) && record.latitude != null && record.longitude != null)
            mapTile(context, record.latitude!!, record.longitude!!) else null
        val mapSize = if (map != null) base * .22f else 0f
        val lines = mutableListOf<String>()
        if (prefs.getBoolean("watermark_date", true)) lines.add(record.capturedAt.replace("T", " ") + " " + record.timeZone)
        if (prefs.getBoolean("watermark_gps", true)) lines.add(if (record.latitude != null) fmtLocation(record) else "Location unavailable")
        if (prefs.getBoolean("watermark_accuracy", true) && record.accuracy != null) lines.add("Accuracy ±${record.accuracy!!.toInt()} m")
        if (prefs.getBoolean("watermark_address", true) && record.address.isNotBlank()) lines.add(record.address)
        if (prefs.getBoolean("watermark_compass", true) && record.heading != null) lines.add("Heading ${record.heading!!.toInt()}°")
        if (prefs.getBoolean("watermark_project", true) && record.project.isNotBlank()) lines.add(record.project)
        if (prefs.getBoolean("watermark_caption", true) && record.caption.isNotBlank()) lines.add(record.caption)
        if (lines.isEmpty()) lines.add("GeoTag Worksite Camera")
        val font = (base * .035f * prefs.getInt("watermark_text_scale", 100) / 100f).coerceIn(17f, 34f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = font; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val lineHeight = font * 1.35f
        val maxTextWidth = (w * .62f - mapSize - pad * 3).coerceAtLeast(180f)
        val fittedLines = lines.take(7).map { value ->
            var line = value
            while (paint.measureText(line) > maxTextWidth && line.length > 12) line = line.dropLast(3) + "…"
            line
        }
        val heightNeeded = max(mapSize, fittedLines.size * lineHeight) + pad * 2
        val widthNeeded = min(w * .78f, maxTextWidth + mapSize + pad * 3)
        val right = prefs.getBoolean("watermark_right", false)
        val left = if (right) w - widthNeeded - pad else pad
        val top = h - heightNeeded - pad
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = prefs.getInt("watermark_alpha", 80).coerceIn(35, 95) * 255 / 100
        }
        canvas.drawRoundRect(RectF(left, top, left + widthNeeded, top + heightNeeded), 18f, 18f, background)
        var textX = left + pad
        if (map != null) {
            val mapLeft = if (right) left + widthNeeded - mapSize - pad else left + pad
            val rect = RectF(mapLeft, top + pad, mapLeft + mapSize, top + pad + mapSize)
            canvas.drawBitmap(map, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val attrib = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10f; setShadowLayer(2f, 1f, 1f, Color.BLACK) }
            canvas.drawText("© OpenStreetMap contributors", rect.left + 3f, rect.bottom - 4f, attrib)
            map.recycle()
            textX = if (right) left + pad else left + mapSize + pad * 1.5f
        }
        fittedLines.forEachIndexed { index, line ->
            val textWidth = maxTextWidth
            val oldSize = paint.textSize
            while (paint.measureText(line) > textWidth && paint.textSize > 12f) paint.textSize -= 1f
            canvas.drawText(line, textX, top + pad + (index + 1) * lineHeight, paint)
            paint.textSize = oldSize
        }
        return bitmap
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
        if (prefs.getBoolean("watermark_address", true) && record.address.isNotBlank()) lines.add(record.address)
        if (prefs.getBoolean("watermark_compass", true) && record.heading != null) lines.add("Heading: ${record.heading!!.toInt()}°")
        if (prefs.getBoolean("watermark_caption", true) && record.caption.isNotBlank()) lines.add(record.caption)
        if (lines.isEmpty()) lines.add("GeoTag Worksite Camera")
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = ((base * .026f).coerceIn(18f, 30f) * prefs.getInt("watermark_text_scale", 100) / 100f)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val lineHeight = textPaint.textSize * 1.3f
        val maxLine = lines.maxOf { textPaint.measureText(it) }
        val blockWidth = (maxLine + if (showMap) mapSize + pad else 0f + pad * 2).coerceAtMost(bitmap.width * .94f)
        val blockHeight = max(mapSize.takeIf { showMap } ?: 0f, lines.size * lineHeight) + pad
        val left = if (right) bitmap.width - blockWidth - pad else pad
        val top = bitmap.height - blockHeight - pad
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = (prefs.getInt("watermark_alpha", 80).coerceIn(35, 95) * 255 / 100) }
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
