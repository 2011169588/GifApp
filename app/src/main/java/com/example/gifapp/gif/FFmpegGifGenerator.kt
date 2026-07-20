package com.example.gifapp.gif

import android.graphics.Bitmap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * GIF generator using FFmpeg's palettegen + paletteuse.
 * Supports batched processing to control memory usage.
 */
object FFmpegGifGenerator {

    suspend fun generateFromVideo(
        videoPath: String, width: Int, height: Int, segmentCount: Int, gapRatio: Float,
        outputWidth: Int, outputHeight: Int, fps: Int = 10, loopForever: Boolean = true,
        colorCount: Int = 256, useBayerDither: Boolean = false, losslessOptimize: Boolean = false,
        parallelCount: Int = 1, outputDir: File,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<File> = withContext(Dispatchers.IO) {
        val allResults = mutableListOf<File>()
        var globalProgress = 0

        // Process in batches of parallelCount
        for (batchStart in 0 until segmentCount step parallelCount) {
            val batchEnd = minOf(batchStart + parallelCount, segmentCount)
            val batchIndices = (batchStart until batchEnd).toList()
            val batchResults = buildAndRun(
                inputArg = "-i \"$videoPath\"",
                frameRateArg = "fps=$fps",
                segmentCount = segmentCount, batchIndices = batchIndices,
                startOffset = batchStart, width = width, height = height, gapRatio = gapRatio,
                outputWidth = outputWidth, outputHeight = outputHeight,
                loopForever = loopForever, colorCount = colorCount,
                useBayerDither = useBayerDither, losslessOptimize = losslessOptimize,
                outputDir = outputDir
            )
            allResults.addAll(batchResults)
            globalProgress += batchResults.size
            onProgress(globalProgress, segmentCount)
        }
        allResults
    }

    suspend fun generateFromBitmaps(
        frames: List<Bitmap>, segmentCount: Int, gapRatio: Float,
        outputWidth: Int, outputHeight: Int, fps: Int = 10, frameDelayMs: Int = 100,
        loopForever: Boolean = true, colorCount: Int = 256, useBayerDither: Boolean = false,
        losslessOptimize: Boolean = false, parallelCount: Int = 1, outputDir: File,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<File> = withContext(Dispatchers.IO) {
        if (frames.isEmpty()) return@withContext emptyList()

        val refBitmap = frames.first()
        val srcW = refBitmap.width; val srcH = refBitmap.height
        val segH = computeSegHeight(srcH, segmentCount, gapRatio)
        val gapH = (srcH * gapRatio).toInt()

        // Save frames as temp PNGs
        val tempDir = File(outputDir, "_frames").apply { mkdirs() }
        tempDir.listFiles()?.forEach { it.delete() }
        for ((i, bm) in frames.withIndex()) {
            FileOutputStream(File(tempDir, "f_${"%03d".format(i)}.png")).use {
                bm.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        val gifFps = (1000f / frameDelayMs).coerceIn(1f, 30f).toInt()
        val inputPattern = "${tempDir.absolutePath}/f_%03d.png"

        val allResults = mutableListOf<File>()
        var globalProgress = 0

        try {
            for (batchStart in 0 until segmentCount step parallelCount) {
                val batchEnd = minOf(batchStart + parallelCount, segmentCount)
                val batchIndices = (batchStart until batchEnd).toList()
                val batchResults = buildAndRun(
                    inputArg = "-framerate $gifFps -i \"$inputPattern\"",
                    frameRateArg = "fps=$gifFps",
                    segmentCount = segmentCount, batchIndices = batchIndices,
                    startOffset = batchStart, width = srcW, height = srcH, gapRatio = gapRatio,
                    outputWidth = outputWidth, outputHeight = outputHeight,
                    loopForever = loopForever, colorCount = colorCount,
                    useBayerDither = useBayerDither, losslessOptimize = losslessOptimize,
                    outputDir = outputDir
                )
                allResults.addAll(batchResults)
                globalProgress += batchResults.size
                onProgress(globalProgress, segmentCount)
            }
        } finally {
            tempDir.listFiles()?.forEach { it.delete() }; tempDir.delete()
        }
        allResults
    }

    // ---- Build & execute a single FFmpeg command for a batch of segments ----

    private fun buildAndRun(
        inputArg: String, frameRateArg: String,
        segmentCount: Int, batchIndices: List<Int>,
        startOffset: Int, width: Int, height: Int, gapRatio: Float,
        outputWidth: Int, outputHeight: Int,
        loopForever: Boolean, colorCount: Int,
        useBayerDither: Boolean, losslessOptimize: Boolean,
        outputDir: File
    ): List<File> {
        val batchSize = batchIndices.size
        val segH = computeSegHeight(height, segmentCount, gapRatio)
        val gapH = (height * gapRatio).toInt()
        val filterParts = mutableListOf<String>()

        // Split input into batchSize streams (or skip split if only 1)
        val streamLabels = batchIndices.indices.joinToString("") { "[b$it]" }
        filterParts.add("$frameRateArg" + if (batchSize > 1) ",split=$batchSize$streamLabels" else streamLabels)

        val mapArgs = mutableListOf<String>()
        val outputFiles = mutableListOf<File>()

        for ((localIdx, segIdx) in batchIndices.withIndex()) {
            val y = segIdx * (segH + gapH)
            val label = "seg$localIdx"
            filterParts.add("[b$localIdx]crop=$width:$segH:0:$y,scale=$outputWidth:$outputHeight:flags=lanczos,split[${label}a][${label}b]")
            val pal = "palettegen=stats_mode=diff:max_colors=$colorCount"
            val dither = if (useBayerDither) "bayer:bayer_scale=5" else "floyd_steinberg"
            filterParts.add("[${label}a]$pal[pal$localIdx]")
            filterParts.add("[${label}b][pal$localIdx]paletteuse=dither=$dither[out$localIdx]")

            val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
            outputFiles.add(outFile)
            val loop = if (loopForever) "-loop 0 " else ""
            val opt = if (losslessOptimize) "-gifflags +offsetting " else ""
            mapArgs.add("$loop$opt-map \"[out$localIdx]\" \"${outFile.absolutePath}\"")
        }

        val cmd = "-y $inputArg -filter_complex \"${filterParts.joinToString("; ")}\" ${mapArgs.joinToString(" ")}"
        return executeFFmpeg(cmd, outputFiles)
    }

    private fun computeSegHeight(totalHeight: Int, segmentCount: Int, gapRatio: Float): Int {
        if (segmentCount <= 1) return totalHeight
        val gapH = (totalHeight * gapRatio).toInt()
        return ((totalHeight - gapH * (segmentCount - 1)) / segmentCount).coerceAtLeast(1)
    }

    private fun executeFFmpeg(cmd: String, outputFiles: List<File>): List<File> {
        val session = try { FFmpegKit.execute(cmd) } catch (e: Throwable) {
            throw RuntimeException("FFmpeg调用崩溃: ${e.message}", e)
        }
        val returnCode = session.returnCode
        val logs = session.allLogs.joinToString("\n") { it.message }
        if (ReturnCode.isSuccess(returnCode)) {
            return outputFiles.filter { it.exists() && it.length() > 0L }
        }
        throw RuntimeException("FFmpeg失败(code=$returnCode)\n${logs.takeLast(2000)}")
    }
}
