package com.parabg.geotagcamera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object VideoWatermarkExporter {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun export(context: Context, record: MediaRecord, onResult: (Result<Uri>) -> Unit) {
        require(record.type == "VIDEO") { "Only videos can be exported by this processor" }
        val app = context.applicationContext
        worker.execute {
            var overlay: android.graphics.Bitmap? = null
            var output: File? = null
            try {
                val retriever = MediaMetadataRetriever()
                val width: Int
                val height: Int
                try {
                    retriever.setDataSource(app, Uri.parse(record.uri))
                    var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                    var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) { val temp = w; w = h; h = temp }
                    val scale = minOf(1f, 1920f / maxOf(w, h).toFloat())
                    width = (w * scale).toInt().coerceAtLeast(640)
                    height = (h * scale).toInt().coerceAtLeast(640)
                } finally {
                    retriever.release()
                }
                overlay = AppStore.videoWatermarkBitmap(app, record, width, height)
                output = File(app.cacheDir, "GTC_Watermarked_${System.currentTimeMillis()}.mp4")
                val bitmap = overlay ?: throw IllegalStateException("Video watermark could not be prepared")
                main.post {
                    try {
                        val videoOverlay = BitmapOverlay.createStaticBitmapOverlay(bitmap)
                        val effect = OverlayEffect(listOf(videoOverlay))
                        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(record.uri)))
                            .setEffects(Effects(emptyList(), listOf(effect)))
                            .build()
                        val transformer = Transformer.Builder(app)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    worker.execute {
                                        try {
                                            val uri = saveToGallery(app, output!!)
                                            output!!.delete()
                                            bitmap.recycle()
                                            main.post { onResult(Result.success(uri)) }
                                        } catch (e: Exception) {
                                            output!!.delete()
                                            bitmap.recycle()
                                            main.post { onResult(Result.failure(e)) }
                                        }
                                    }
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    output!!.delete()
                                    bitmap.recycle()
                                    onResult(Result.failure(exportException))
                                }
                            })
                            .build()
                        transformer.start(edited, output!!.absolutePath)
                    } catch (e: Exception) {
                        output?.delete()
                        bitmap.recycle()
                        onResult(Result.failure(e))
                    }
                }
            } catch (e: Exception) {
                overlay?.recycle()
                output?.delete()
                main.post { onResult(Result.failure(e)) }
            }
        }
    }

    private fun saveToGallery(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GeoTag Worksite Camera/Exports")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Phone Gallery storage is unavailable")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            } ?: error("Unable to save watermarked video")
            if (Build.VERSION.SDK_INT >= 29) {
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, ready, null, null)
            }
            return uri
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }
}