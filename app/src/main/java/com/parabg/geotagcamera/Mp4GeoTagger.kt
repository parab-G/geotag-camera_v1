package com.parabg.geotagcamera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object Mp4GeoTagger {
    fun addLocation(input: File, output: File, latitude: Double, longitude: Double): Boolean {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(input.absolutePath)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
                if (rotation in listOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
            } catch (_: Exception) { }
            muxer.setLocation(latitude.toFloat(), longitude.toFloat())
            val trackMap = mutableMapOf<Int, Int>()
            var bufferSize = 1024 * 1024
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val max = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                if (max > bufferSize && max < 32 * 1024 * 1024) bufferSize = max
                trackMap[i] = muxer.addTrack(format)
            }
            if (trackMap.isEmpty()) return false
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            for (inputTrack in 0 until extractor.trackCount) {
                extractor.selectTrack(inputTrack)
                val outputTrack = trackMap[inputTrack] ?: continue
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(outputTrack, buffer, info)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(inputTrack)
            }
            muxer.stop()
            true
        } catch (_: Exception) {
            output.delete()
            false
        } finally {
            try { extractor.release() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
        }
    }
}
