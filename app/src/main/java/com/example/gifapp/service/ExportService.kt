package com.example.gifapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.gifapp.MainActivity
import com.example.gifapp.R
import com.example.gifapp.gif.FFmpegGifGenerator
import com.example.gifapp.model.ExportTask
import com.example.gifapp.model.GifConfig
import com.example.gifapp.model.GifResult
import com.example.gifapp.model.TaskStatus
import com.example.gifapp.util.ImageUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Service 导出状态 */
sealed interface ExportState {
    data object Idle : ExportState
    data class Progress(val taskId: String, val current: Int, val total: Int) : ExportState
    data class Done(val taskId: String, val results: List<GifResult>) : ExportState
    data class Failed(val taskId: String, val error: String) : ExportState
    data class Cancelled(val taskId: String) : ExportState
}

class ExportService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gif_export"
        private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
        val state: StateFlow<ExportState> = _state

        fun cancelExport(context: Context, taskId: String) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra("action", "cancel")
                putExtra("taskId", taskId)
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionList = mutableListOf<com.arthenica.ffmpegkit.FFmpegSession>()
    @Volatile private var isRunning = false
    private val cancelled = AtomicBoolean(false)

    // region Notification

    private fun createChannel() {
        val c = NotificationChannel(CHANNEL_ID, "GIF 导出", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(c)
    }

    private fun buildNotification(title: String, progress: Int, total: Int, isFinal: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GIF 导出")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(!isFinal)
            .apply {
                if (!isFinal && total > 0) setProgress(total, progress, false)
            }
            .build()
    }

    // endregion

    // region Task file IO

    private data class TaskParams(
        val taskId: String, val sourceType: ExportTask.SourceType,
        val videoUri: String?, val imageUris: List<String>,
        val segmentCount: Int, val gapRatio: Float, val config: GifConfig,
        val sourceWidth: Int, val sourceHeight: Int,
        val gifSourcePath: String? = null
    )

    private fun readTaskParams(taskId: String): TaskParams? {
        return try {
            val file = File(filesDir, "tasks.json")
            if (!file.exists()) return null
            val json = JSONArray(file.readText())
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                if (obj.getString("id") != taskId) continue
                val cfg = GifConfig(
                    outputWidth = obj.getInt("outputWidth"), outputHeight = obj.getInt("outputHeight"),
                    frameDelayMs = obj.optInt("frameDelayMs", 100),
                    maxVideoFrames = obj.optInt("maxVideoFrames", 20),
                    loopForever = obj.optBoolean("loopForever", true),
                    colorCount = obj.optInt("colorCount", 256),
                    useBayerDither = obj.optBoolean("useBayerDither", false),
                    losslessOptimize = obj.optBoolean("losslessOptimize", true),
                    parallelCount = obj.optInt("parallelCount", 1),
                    paletteStatsFull = obj.optBoolean("paletteStatsFull", false),
                    transparencyOptimize = obj.optBoolean("transparencyOptimize", false),
                    frameDedup = obj.optBoolean("frameDedup", false)
                )
                val imageUris = mutableListOf<String>()
                val ia = obj.optJSONArray("imageUris")
                if (ia != null) for (j in 0 until ia.length()) imageUris.add(ia.getString(j))
                return TaskParams(
                    taskId = taskId,
                    sourceType = if (obj.getString("sourceType") == "VIDEO") ExportTask.SourceType.VIDEO else ExportTask.SourceType.IMAGES,
                    videoUri = obj.optString("videoUri", null)?.ifEmpty { null },
                    imageUris = imageUris,
                    segmentCount = obj.getInt("segmentCount"),
                    gapRatio = obj.getDouble("gapRatio").toFloat(),
                    config = cfg,
                    sourceWidth = obj.optInt("sourceWidth", 0),
                    sourceHeight = obj.optInt("sourceHeight", 0),
                    gifSourcePath = obj.optString("gifSourcePath", null)?.takeIf { it.isNotEmpty() }
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** 原子写 tasks.json：先 .tmp 再 rename */
    private fun updateTaskInFile(taskId: String, status: TaskStatus, progress: Int = 0, total: Int = 1, results: List<GifResult> = emptyList(), error: String? = null) {
        try {
            val file = File(filesDir, "tasks.json")
            if (!file.exists()) return
            val json = JSONArray(file.readText())
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                if (obj.getString("id") != taskId) continue
                obj.put("status", status.name)
                obj.put("progress", progress); obj.put("total", total)
                if (error != null) obj.put("errorMessage", error)
                val ra = JSONArray()
                for (r in results) {
                    ra.put(JSONObject().apply {
                        put("index", r.index); put("filePath", r.filePath)
                        put("width", r.width); put("height", r.height)
                        put("sizeBytes", r.sizeBytes); put("frameCount", r.frameCount)
                    })
                }
                obj.put("results", ra)
            }
            val tmp = File(filesDir, "tasks.json.tmp")
            tmp.writeText(json.toString(2))
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    // endregion

    // region Lifecycle

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        // 处理取消
        if (intent.getStringExtra("action") == "cancel") {
            cancelled.set(true)
            sessionList.forEach { it.cancel() }
            sessionList.clear()
            return START_NOT_STICKY
        }

        val taskId = intent.getStringExtra("taskId") ?: return START_NOT_STICKY
        if (isRunning) return START_NOT_STICKY // 防重入
        isRunning = true
        cancelled.set(false)

        val params = readTaskParams(taskId) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification("准备导出…", 0, params.segmentCount))

        // WakeLock — 10 分钟超时
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gif:export")
            .apply { acquire(10 * 60 * 1000L) }

        serviceScope.launch {
            try {
                runExport(params)
            } catch (_: CancellationException) {
                onFinish(params.taskId, TaskStatus.CANCELLED, error = "已取消")
            } catch (e: Exception) {
                onFinish(params.taskId, TaskStatus.FAILED, error = e.message?.take(200) ?: "未知错误")
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        super.onDestroy()
    }

    // endregion

    // region Export

    private suspend fun runExport(params: TaskParams) {
        val outputDir = File(getExternalFilesDir("GIFSplitter") ?: filesDir, params.taskId).apply { mkdirs() }

        // 写入崩溃标记
        File(filesDir, "crash_marker").writeText(params.taskId)
        updateTaskInFile(params.taskId, TaskStatus.RUNNING)

        var results = emptyList<GifResult>()
        var tempVideo: File? = null

        try {
            results = when (params.sourceType) {
                ExportTask.SourceType.VIDEO -> {
                    val videoUri = params.videoUri ?: throw Exception("视频源丢失")
                    tempVideo = File(outputDir, "input.mp4")
                    contentResolver.openInputStream(Uri.parse(videoUri))?.use { input ->
                        tempVideo!!.outputStream().use { input.copyTo(it) }
                    } ?: throw Exception("无法读取视频文件")
                    val files = executeBatched(
                        params.taskId,
                        computeSourceWidth(params), computeSourceHeight(params),
                        params.segmentCount, params.gapRatio, params.config, outputDir
                    ) { cur, tot ->
                        showNotification("段 $cur / $tot", cur, tot)
                    }
                    // 重建带实际尺寸的结果
                    val maxW = params.config.outputWidth
                    val maxH = params.config.outputHeight
                    files.mapIndexed { i, f -> GifResult(i, f.absolutePath, maxW, maxH, f.length(), 1) }
                }
                ExportTask.SourceType.IMAGES -> {
                    val frames = if (params.gifSourcePath != null) {
                        // GIF 源 → 先用 FFmpeg 拆帧
                        extractGifFrames(params.gifSourcePath!!, outputDir)
                    } else {
                        params.imageUris.mapNotNull { ImageUtils.loadBitmapFromUri(this@ExportService, Uri.parse(it)) }
                    }
                    if (frames.isEmpty()) throw Exception("未能加载图片")
                    // 使用多图生成，分批执行
                    runImagesBatchExport(params, frames, outputDir)
                }
            }

            // 成功
            File(filesDir, "crash_marker").delete()
            updateTaskInFile(params.taskId, TaskStatus.COMPLETED, progress = results.size, total = results.size, results = results)
            _state.value = ExportState.Done(params.taskId, results)
            showDoneNotification(results.size)

        } catch (e: Exception) {
            // 错误处理
            val errMsg = e.message?.take(200) ?: "未知错误"
            val status = if (cancelled.get()) TaskStatus.CANCELLED else TaskStatus.FAILED
            onFinish(params.taskId, status, error = errMsg)
        } finally {
            tempVideo?.delete()
            wakeLock?.let { if (it.isHeld) it.release() }
            isRunning = false
            stopSelf()
        }
    }

    /** 图片源分批导出 — batch 内多条 segment 合并为一条 FFmpeg 命令同时输出 */
    private suspend fun runImagesBatchExport(params: TaskParams, frames: List<Bitmap>, outputDir: File): List<GifResult> {
        val results = mutableListOf<GifResult>()

        // 统一所有帧的尺寸
        val targetW = frames.first().width
        val targetH = frames.first().height
        val uniformFrames = frames.map { normalizeFrame(it, targetW, targetH) }

        val segH = computeSegHeight(targetH, params.segmentCount, params.gapRatio)
        val gapH = (targetH * params.gapRatio).toInt()
        val batchSize = params.config.parallelCount.coerceIn(1, params.segmentCount)
        val gifFps = params.config.maxFrameRate.coerceIn(1, 30)

        for (batchStart in 0 until params.segmentCount step batchSize) {
            if (cancelled.get()) break
            val batchEnd = minOf(batchStart + batchSize, params.segmentCount)
            val batchIndices = (batchStart until batchEnd).toList()

            // 对 batch 中每条 segment：裁剪帧 → 写入独立目录
            val gifFlags = buildString {
                if (params.config.losslessOptimize) append("+offsetting")
                if (params.config.transparencyOptimize) { if (isNotEmpty()) append(","); append("+transparency") }
            }
            val opt = if (gifFlags.isNotEmpty()) "-gifflags ${gifFlags} " else ""
            val inputArgs = mutableListOf<String>()
            val filterParts = mutableListOf<String>()
            val mapArgs = mutableListOf<String>()
            val outputFiles = mutableListOf<File>()

            for ((localIdx, segIdx) in batchIndices.withIndex()) {
                val y = segIdx * (segH + gapH)
                val segDir = File(outputDir, "_b${batchStart}_s$segIdx").apply { mkdirs() }

                // 裁剪帧
                var prevCrop: Bitmap? = null
                var frameSeq = 0
                for ((fi, frame) in uniformFrames.withIndex()) {
                    val safeY = y.coerceIn(0, frame.height - 1)
                    val safeCropH = segH.coerceAtMost(frame.height - safeY).coerceAtLeast(1)
                    val crop = Bitmap.createBitmap(frame, 0, safeY, frame.width, safeCropH)
                    val isDup = params.config.frameDedup && prevCrop != null && bitmapsEqual(prevCrop!!, crop)
                    if (isDup) { crop.recycle() } else {
                        FileOutputStream(File(segDir, "f_${"%03d".format(frameSeq)}.png")).use {
                            crop.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        frameSeq++; prevCrop?.recycle(); prevCrop = crop
                    }
                }
                prevCrop?.recycle()
                if (frameSeq == 0) continue

                val label = "s$localIdx"
                inputArgs.add("-framerate $gifFps -i \"${segDir.absolutePath}/f_%03d.png\"")
                filterParts.add("[${label}]scale=${params.config.outputWidth}:${params.config.outputHeight}:flags=lanczos[${label}o]")
                val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
                outputFiles.add(outFile)
                mapArgs.add("-map \"[${label}o]\" -loop ${if (params.config.loopForever) 0 else 1} $opt\"${outFile.absolutePath}\"")
            }

            if (outputFiles.isEmpty()) break

            // 一条 FFmpeg 命令处理 batch 内所有 segment
            val cmd = "-y ${inputArgs.joinToString(" ")} -filter_complex \"${filterParts.joinToString("; ")}\" ${mapArgs.joinToString(" ")}"
            val batchOk = try {
                runFfmpeg(cmd)
            } catch (_: Exception) { false }

            // 收尾
            for ((localIdx, segIdx) in batchIndices.withIndex()) {
                File(outputDir, "_b${batchStart}_s$segIdx").let { it.listFiles()?.forEach { f -> f.delete() }; it.delete() }
                if (batchOk) {
                    val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
                    if (outFile.exists() && outFile.length() > 0) {
                        results.add(GifResult(segIdx, outFile.absolutePath, params.config.outputWidth, params.config.outputHeight, outFile.length(), 1))
                    }
                }
                val p = batchStart + localIdx + 1
                _state.value = ExportState.Progress(params.taskId, p, params.segmentCount)
                updateTaskInFile(params.taskId, TaskStatus.RUNNING, progress = p, total = params.segmentCount)
                showNotification("段 $p / ${params.segmentCount}", p, params.segmentCount)
            }
            if (!batchOk) break
        }
        return results
    }

    /** 执行一次 FFmpeg，支持取消 */
    private fun runFfmpeg(cmd: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val session = FFmpegKit.executeAsync(cmd) { s ->
            success = ReturnCode.isSuccess(s.returnCode)
            latch.countDown()
        }
        sessionList.add(session)

        while (true) {
            if (latch.await(1, TimeUnit.SECONDS)) break
            if (cancelled.get()) {
                session.cancel()
                latch.await()
                success = false
                break
            }
        }
        sessionList.remove(session)
        return success
    }

    /** 视频分段批处理（复用 FFmpegGifGenerator） */
    private suspend fun executeBatched(
        taskId: String, srcW: Int, srcH: Int, segCount: Int, gapRatio: Float,
        cfg: GifConfig, outputDir: File,
        onBatch: suspend (Int, Int) -> Unit
    ): List<File> {
        val segH = computeSegHeight(srcH, segCount, gapRatio)
        val results = mutableListOf<File>()
        val batchSize = cfg.parallelCount.coerceIn(1, segCount)

        for (batchStart in 0 until segCount step batchSize) {
            if (cancelled.get()) break
            val batchEnd = minOf(batchStart + batchSize, segCount)
            val batchIndices = (batchStart until batchEnd).toList()
            val batchSegH = segH
            val batchGapH = (srcH * gapRatio).toInt()
            val filterParts = mutableListOf<String>()
            val mapArgs = mutableListOf<String>()
            val outputFiles = mutableListOf<File>()

            val streamLabels = batchIndices.indices.joinToString("") { "[b$it]" }
            filterParts.add("fps=${cfg.maxFrameRate.coerceIn(1, 30)}" + if (batchIndices.size > 1) ",split=${batchIndices.size}$streamLabels" else streamLabels)

            for ((localIdx, segIdx) in batchIndices.withIndex()) {
                val y = segIdx * (batchSegH + batchGapH)
                val label = "seg$localIdx"
                filterParts.add("[b$localIdx]crop=$srcW:$batchSegH:0:$y,scale=${cfg.outputWidth}:${cfg.outputHeight}:flags=lanczos,split[${label}a][${label}b]")
                val statsMode = if (cfg.paletteStatsFull) "full" else "diff"
                filterParts.add("[${label}a]palettegen=stats_mode=$statsMode:max_colors=${cfg.colorCount}[pal$localIdx]")
                val dither = if (cfg.useBayerDither) "bayer:bayer_scale=5" else "floyd_steinberg"
                filterParts.add("[${label}b][pal$localIdx]paletteuse=dither=$dither[out$localIdx]")
                val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
                outputFiles.add(outFile)
                val loop = if (cfg.loopForever) "-loop 0 " else ""
                val gifFlags = buildString {
                    if (cfg.losslessOptimize) append("+offsetting")
                    if (cfg.transparencyOptimize) { if (isNotEmpty()) append(","); append("+transparency") }
                }
                val opt = if (gifFlags.isNotEmpty()) "-gifflags ${gifFlags} " else ""
                mapArgs.add("$loop$opt-map \"[out$localIdx]\" \"${outFile.absolutePath}\"")
            }

            val inputArg = "-i \"${outputDir.absolutePath}/input.mp4\""
            val cmd = "-y $inputArg -filter_complex \"${filterParts.joinToString("; ")}\" ${mapArgs.joinToString(" ")}"
            val batchOk = runFfmpeg(cmd)

            if (batchOk) {
                results.addAll(outputFiles.filter { it.exists() && it.length() > 0L })
            } else {
                if (cancelled.get()) break
            }

            val p = minOf(batchEnd, segCount)
            onBatch(p, segCount)
            _state.value = ExportState.Progress(taskId = taskId, current = p, total = segCount)
        }
        return results
    }

    /** 用 FFmpeg 拆解 GIF 为 Bitmap 列表 */
    private fun extractGifFrames(gifPath: String, outputDir: File): List<Bitmap> {
        val frameDir = File(outputDir, "_gif_frames").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val cmd = "-y -i \"$gifPath\" -vsync 0 \"${frameDir.absolutePath}/f_%03d.png\""
        val session = try { com.arthenica.ffmpegkit.FFmpegKit.execute(cmd) }
            catch (e: Throwable) { throw RuntimeException("FFmpeg 拆帧失败: ${e.message}") }
        if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
            throw RuntimeException("FFmpeg 拆帧失败")
        }
        val files = frameDir.listFiles()?.filter { it.name.startsWith("f_") }?.sorted() ?: emptyList()
        return files.mapNotNull { f ->
            try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }
            catch (_: Exception) { null }
        }.also { frameDir.listFiles()?.forEach { it.delete() }; frameDir.delete() }
    }

    /** 将 Bitmap 按中心裁剪方式缩放到目标尺寸（防止多图比例不同时越界） */
    private fun normalizeFrame(bm: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (bm.width == targetW && bm.height == targetH) return bm
        val scale = maxOf(targetW.toFloat() / bm.width, targetH.toFloat() / bm.height)
        val scaledW = (bm.width * scale).toInt()
        val scaledH = (bm.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bm, scaledW, scaledH, true)
        val x = ((scaledW - targetW) / 2).coerceAtLeast(0)
        val y = ((scaledH - targetH) / 2).coerceAtLeast(0)
        val cropW = minOf(targetW, scaledW)
        val cropH = minOf(targetH, scaledH)
        return Bitmap.createBitmap(scaled, x, y, cropW, cropH)
    }

    /** 逐像素比较两个 Bitmap 是否完全相同 */
    private fun bitmapsEqual(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pixelsA = IntArray(a.width * a.height)
        val pixelsB = IntArray(b.width * b.height)
        a.getPixels(pixelsA, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pixelsB, 0, b.width, 0, 0, b.width, b.height)
        return pixelsA.contentEquals(pixelsB)
    }

    private fun computeSourceHeight(params: TaskParams): Int {
        if (params.sourceHeight > 0) return params.sourceHeight
        return 800 // fallback
    }

    private fun computeSourceWidth(params: TaskParams): Int {
        if (params.sourceWidth > 0) return params.sourceWidth
        return 480 // fallback
    }

    private fun computeSegHeight(totalHeight: Int, segmentCount: Int, gapRatio: Float): Int {
        if (segmentCount <= 1) return totalHeight
        val gapH = (totalHeight * gapRatio).toInt()
        return ((totalHeight - gapH * (segmentCount - 1)) / segmentCount).coerceAtLeast(1)
    }

    // endregion

    // region UI Updates

    private fun showNotification(title: String, progress: Int, total: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, progress, total))
    }

    private fun showDoneNotification(count: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("导出完成 · 共 $count 段", count, count, isFinal = true))
        // 2 秒后移除
        serviceScope.launch {
            delay(2000)
            nm.cancel(NOTIFICATION_ID)
        }
    }

    private fun onFinish(taskId: String, status: TaskStatus, error: String? = null) {
        val results = emptyList<GifResult>()
        val errMsg = if (status == TaskStatus.CANCELLED) "已取消" else error
        updateTaskInFile(taskId, status, progress = 0, total = 1, error = error)
        if (status == TaskStatus.CANCELLED) {
            _state.value = ExportState.Cancelled(taskId)
        } else {
            _state.value = ExportState.Failed(taskId, errMsg ?: "未知错误")
        }
        File(filesDir, "crash_marker").delete()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(
            if (status == TaskStatus.CANCELLED) "已取消" else "导出失败",
            0, 1, isFinal = true
        ))
        serviceScope.launch { delay(2000); nm.cancel(NOTIFICATION_ID) }
    }

    // endregion
}
