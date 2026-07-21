package com.example.gifapp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/**
 * Utility functions for image and video processing.
 */
object ImageUtils {

    /**
     * Load a bitmap from a content URI with size limiting.
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxWidth: Int = 2048, maxHeight: Int = 4096): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // Decode bounds only
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()

            // Calculate sample size
            val scale = minOf(
                opts.outWidth / maxWidth,
                opts.outHeight / maxHeight
                ).coerceAtLeast(1)

            // Decode with sampling
            val sampleOpts = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val inputStream2 = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, sampleOpts)
            inputStream2.close()

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extract frames from a video. Returns a list of Bitmaps sampled at regular intervals.
     * Uses OPTION_CLOSEST (not CLOSEST_SYNC) to avoid getting duplicate keyframes.
     */
    fun extractVideoFrames(context: Context, uri: Uri, maxFrames: Int = 15): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)

            val durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            val durationMs = durationStr?.toLongOrNull() ?: return frames

            if (durationMs <= 0) return frames

            // Calculate frame interval — ensure we get the right number of unique frames
            val actualCount = maxFrames.coerceAtMost((durationMs / 33).toInt()) // max ~30fps
            val interval = (durationMs / actualCount).coerceAtLeast(33L)

            // Collect timestamps evenly across the video
            val timestamps = mutableListOf<Long>()
            for (i in 0 until actualCount) {
                timestamps.add(i * interval)
            }
            // Ensure last frame is included
            if (timestamps.last() < durationMs - interval / 2) {
                timestamps.add(durationMs - 1)
            }

            for (timeUs in timestamps.map { it * 1000 }) {
                val bitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST  // NOT CLOSEST_SYNC — ensures unique frames
                )
                if (bitmap != null) {
                    frames.add(bitmap)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return frames
    }

    /**
     * Save a bitmap to a cache file as PNG.
     */
    fun saveBitmapToCache(context: Context, bitmap: Bitmap, fileName: String): File {
        val file = File(context.cacheDir, fileName)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    /**
     * Rotate a bitmap.
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
