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
        val sourceWidth: Int, val sourceHeight: Int
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
                    parallelCount = obj.optInt("parallelCount", 1)
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
                    sourceHeight = obj.optInt("sourceHeight", 0)
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
                        computeSourceWidth(params), computeSourceHeight(params),
                        params.segmentCount, params.gapRatio, params.config, outputDir
                    ) { _, _ -> } // 不需要进度
                    // 重建带实际尺寸的结果
                    val maxW = params.config.outputWidth
                    val maxH = params.config.outputHeight
                    files.mapIndexed { i, f -> GifResult(i, f.absolutePath, maxW, maxH, f.length(), 1) }
                }
                ExportTask.SourceType.IMAGES -> {
                    val frames = params.imageUris.mapNotNull { ImageUtils.loadBitmapFromUri(this@ExportService, Uri.parse(it)) }
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

    /** 图片源分批导出 */
    private suspend fun runImagesBatchExport(params: TaskParams, frames: List<Bitmap>, outputDir: File): List<GifResult> {
        val results = mutableListOf<GifResult>()
        val segH = computeSegHeight(computeSourceHeight(params), params.segmentCount, params.gapRatio)

        // 改成逐步执行：每个 segment 跑一次 FFmpeg
        // 但如果 parallelCount > 1，每次处理一批
        val batchSize = params.config.parallelCount.coerceIn(1, params.segmentCount)
        for (batchStart in 0 until params.segmentCount step batchSize) {
            if (cancelled.get()) break
            val batchEnd = minOf(batchStart + batchSize, params.segmentCount)
            val batchCount = batchEnd - batchStart

            // 对这个 batch 中的每个 segment，生成完整的 GIF
            for (segIdx in batchStart until batchEnd) {
                if (cancelled.get()) break
                val localIndex = segIdx - batchStart
                // 裁剪每帧的 segment 区域
                val y = segIdx * (segH + (computeSourceHeight(params) * params.gapRatio).toInt())
                val cropH = segH

                // 创建临时裁剪帧
                val tempDir = File(outputDir, "_batch_$segIdx").apply { mkdirs() }
                for ((fi, frame) in frames.withIndex()) {
                    val cropBitmap = Bitmap.createBitmap(frame, 0, y.coerceAtMost(frame.height - 1),
                        frame.width.coerceAtMost(frame.width), cropH.coerceAtMost(frame.height - y))
                    FileOutputStream(File(tempDir, "f_${"%03d".format(fi)}.png")).use {
                        cropBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (fi > 0) cropBitmap.recycle()
                }

                val gifFps = (1000f / params.config.frameDelayMs).coerceIn(1f, 30f).toInt()
                val inputArg = "-framerate $gifFps -i \"${tempDir.absolutePath}/f_%03d.png\""

                val cmd = "-y $inputArg -vf \"scale=${params.config.outputWidth}:${params.config.outputHeight}:flags=lanczos\" " +
                        "-loop ${if (params.config.loopForever) 0 else 1} " +
                        "${if (params.config.losslessOptimize) "-gifflags +offsetting " else ""}" +
                        "\"${File(outputDir, "segment_${segIdx + 1}.gif").absolutePath}\""

                if (!runFfmpeg(cmd)) break

                // 清理临时帧
                tempDir.listFiles()?.forEach { it.delete() }; tempDir.delete()

                val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
                if (outFile.exists() && outFile.length() > 0) {
                    results.add(GifResult(segIdx, outFile.absolutePath, params.config.outputWidth, params.config.outputHeight, outFile.length(), 1))
                }

                val p = batchStart + localIndex + 1
                _state.value = ExportState.Progress(params.taskId, p, params.segmentCount)
                updateTaskInFile(params.taskId, TaskStatus.RUNNING, progress = p, total = params.segmentCount)
                showNotification("段 $p / ${params.segmentCount}", p, params.segmentCount)
            }
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
        srcW: Int, srcH: Int, segCount: Int, gapRatio: Float,
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
                filterParts.add("[${label}a]palettegen=stats_mode=diff:max_colors=${cfg.colorCount}[pal$localIdx]")
                val dither = if (cfg.useBayerDither) "bayer:bayer_scale=5" else "floyd_steinberg"
                filterParts.add("[${label}b][pal$localIdx]paletteuse=dither=$dither[out$localIdx]")
                val outFile = File(outputDir, "segment_${segIdx + 1}.gif")
                outputFiles.add(outFile)
                val loop = if (cfg.loopForever) "-loop 0 " else ""
                val opt = if (cfg.losslessOptimize) "-gifflags +offsetting " else ""
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
            _state.value = ExportState.Progress(taskId = "", current = p, total = segCount)
        }
        return results
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
