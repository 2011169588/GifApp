package com.example.gifapp.viewmodel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.gifapp.model.ExportTask
import com.example.gifapp.model.GifConfig
import com.example.gifapp.model.GifResult
import com.example.gifapp.model.MediaSource
import com.example.gifapp.model.TaskStatus
import com.example.gifapp.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class EditorViewModel : ViewModel() {

    // ---- Media Source ----
    var mediaSource by mutableStateOf<MediaSource?>(null)
        private set
    var imageUris by mutableStateOf<List<Uri>>(emptyList())
        private set
    var currentImageIndex by mutableIntStateOf(0)
        private set

    // ---- Editor State ----
    var segmentCount by mutableIntStateOf(3)
        private set
    var gapRatio by mutableFloatStateOf(0.03f)
        private set
    var isImporting by mutableStateOf(false)
        private set
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    // ---- GIF Config ----
    var gifConfig by mutableStateOf(GifConfig())
        private set

    // ---- Task System ----
    var tasks by mutableStateOf<List<ExportTask>>(emptyList())
        private set
    var showTaskPanel by mutableStateOf(false)
        private set

    // ---- Legacy state (for backward compat) ----
    var isExporting by mutableStateOf(false)
        private set
    var exportProgress by mutableIntStateOf(0)
        private set
    var exportTotal by mutableIntStateOf(0)
        private set
    var generatedGifs by mutableStateOf<List<GifResult>>(emptyList())
        private set
    var lastSavedDirName by mutableStateOf<String?>(null)
        private set

    var previewBitmap by mutableStateOf<Bitmap?>(null)
        private set

    // ---- Import Error ----
    var showImportError by mutableStateOf(false)
        private set
    var importErrorMessage by mutableStateOf("")

    fun dismissImportError() { showImportError = false; importErrorMessage = "" }

    // ---- Crash Detection ----
    var safeParallelCount by mutableIntStateOf(6)
        private set
    var showCrashWarning by mutableStateOf(false)
        private set
    var showParallelConfirm by mutableStateOf(false)
        private set
    var pendingParallelCount by mutableIntStateOf(0)
        private set

    private var cachedVideoFrames: List<Bitmap> = emptyList()

    // ---- GIF 源（导入时只存文件不拆帧，导出时再拆） ----
    var gifSourcePath by mutableStateOf<String?>(null)
        private set

    // ---- Homepage ----
    var homepageDescription by mutableStateOf("")

    // ---- Update Check ----
    var updateInfo by mutableStateOf(com.example.gifapp.util.UpdateInfo(false))
        private set
    /** 每次启动只自动检查一次 */
    var updateCheckedOnce by mutableStateOf(false)
        private set
    var showUpdateDialog by mutableStateOf(false)
        private set

    fun checkForUpdate() {
        if (updateCheckedOnce) return
        updateCheckedOnce = true
        viewModelScope.launch {
            updateInfo = withContext(Dispatchers.IO) { com.example.gifapp.util.UpdateChecker.check() }
        }
    }

    fun manualCheckForUpdate() {
        viewModelScope.launch {
            updateInfo = withContext(Dispatchers.IO) {
                com.example.gifapp.util.UpdateChecker.check()
            }
        }
    }

    fun dismissUpdateDialog() { showUpdateDialog = false }

    fun showUpdateInfo() {
        if (updateInfo.hasUpdate) {
            showUpdateDialog = true
        }
    }

    // 【修复】使用 Map 追踪每个任务的 Job，防止多任务互相踩踏
    private val activeJobs = mutableMapOf<String, Job>()

    val segmentCountRange: IntRange = 1..20
    val gapRatioRange: ClosedFloatingPointRange<Float> = 0f..0.3f
    val isVideoMode: Boolean get() = mediaSource is MediaSource.Video
    val currentPreviewBitmap: Bitmap? get() = previewBitmap

    val segmentWidth: Int get() = previewBitmap?.width ?: 0
    val segmentHeight: Int get() {
        val bm = previewBitmap ?: return 0
        val usableH = bm.height.toFloat() * (1f - gapRatio)
        return (if (segmentCount > 1) usableH / segmentCount else bm.height.toFloat()).toInt()
    }

    // ---- Config Persistence ----

    fun loadConfig(context: Context) {
        val prefs = context.getSharedPreferences("gif_export_config", Context.MODE_PRIVATE)
        gifConfig = GifConfig(
            outputWidth = prefs.getInt("outputWidth", 480),
            outputHeight = prefs.getInt("outputHeight", 800),
            frameDelayMs = prefs.getInt("frameDelayMs", 1000),
            maxFrameRate = prefs.getInt("maxFrameRate", 15),
            maxVideoFrames = prefs.getInt("maxVideoFrames", 20),
            loopForever = prefs.getBoolean("loopForever", true),
            colorCount = prefs.getInt("colorCount", 256),
            useBayerDither = prefs.getBoolean("useBayerDither", false),
            losslessOptimize = prefs.getBoolean("losslessOptimize", true),
            parallelCount = prefs.getInt("parallelCount", 1),
            paletteStatsFull = prefs.getBoolean("paletteStatsFull", false),
            transparencyOptimize = prefs.getBoolean("transparencyOptimize", false),
            frameDedup = prefs.getBoolean("frameDedup", false)
        )
        segmentCount = prefs.getInt("segmentCount", 3)
        gapRatio = prefs.getFloat("gapRatio", 0.03f)
        safeParallelCount = prefs.getInt("safeParallelCount", 6)
    }

    // 检查崩溃标记
    fun checkCrashMarker(context: Context) {
        val marker = File(context.filesDir, "crash_marker")
        if (marker.exists()) {
            marker.delete()
            showCrashWarning = true
        }
    }

    // 用户确认了崩溃提示
    fun dismissCrashWarning() { showCrashWarning = false }

    // 请求确认是否使用超过安全值的并行数
    fun requestParallelConfirm(count: Int) {
        if (count > safeParallelCount) {
            pendingParallelCount = count
            showParallelConfirm = true
        } else {
            gifConfig = gifConfig.copy(parallelCount = count)
        }
    }

    fun confirmParallel() {
        gifConfig = gifConfig.copy(parallelCount = pendingParallelCount)
        showParallelConfirm = false
    }

    fun dismissParallelConfirm() { showParallelConfirm = false }

    private fun saveConfig(context: Context) {
        val prefs = context.getSharedPreferences("gif_export_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("outputWidth", gifConfig.outputWidth)
            putInt("outputHeight", gifConfig.outputHeight)
            putInt("frameDelayMs", gifConfig.frameDelayMs)
            putInt("maxFrameRate", gifConfig.maxFrameRate)
            putInt("maxVideoFrames", gifConfig.maxVideoFrames)
            putBoolean("loopForever", gifConfig.loopForever)
            putInt("colorCount", gifConfig.colorCount)
            putBoolean("useBayerDither", gifConfig.useBayerDither)
            putBoolean("losslessOptimize", gifConfig.losslessOptimize)
            putInt("parallelCount", gifConfig.parallelCount)
            putBoolean("paletteStatsFull", gifConfig.paletteStatsFull)
            putBoolean("transparencyOptimize", gifConfig.transparencyOptimize)
            putBoolean("frameDedup", gifConfig.frameDedup)
            putInt("segmentCount", segmentCount)
            putFloat("gapRatio", gapRatio)
            apply()
        }
    }

    // ---- Task Persistence ----

    private fun saveTasks(context: Context) {
        val file = File(context.filesDir, "tasks.json")
        try {
            val json = org.json.JSONArray()
            for (t in tasks) {
                val obj = org.json.JSONObject().apply {
                    put("id", t.id); put("createdAt", t.createdAt)
                    put("sourceLabel", t.sourceLabel); put("sourceType", t.sourceType.name)
                    put("segmentCount", t.segmentCount); put("gapRatio", t.gapRatio.toDouble())
                    put("status", t.status.name); put("progress", t.progress); put("total", t.total)
                    put("errorMessage", t.errorMessage ?: "")
                    put("outputWidth", t.config.outputWidth); put("outputHeight", t.config.outputHeight)
                    put("frameDelayMs", t.config.frameDelayMs); put("maxFrameRate", t.config.maxFrameRate); put("maxVideoFrames", t.config.maxVideoFrames)
                    put("loopForever", t.config.loopForever); put("colorCount", t.config.colorCount); put("useBayerDither", t.config.useBayerDither); put("losslessOptimize", t.config.losslessOptimize)
                    put("paletteStatsFull", t.config.paletteStatsFull); put("transparencyOptimize", t.config.transparencyOptimize); put("frameDedup", t.config.frameDedup)
                    put("sourceWidth", t.sourceWidth); put("sourceHeight", t.sourceHeight)
                    t.videoUri?.let { put("videoUri", it) }
                    t.gifSourcePath?.let { put("gifSourcePath", it) }
                    if (t.imageUris.isNotEmpty()) {
                        val ia = org.json.JSONArray(); t.imageUris.forEach { ia.put(it) }; put("imageUris", ia)
                    }
                    val ra = org.json.JSONArray()
                    for (r in t.results) {
                        ra.put(org.json.JSONObject().apply {
                            put("index", r.index); put("filePath", r.filePath)
                            put("width", r.width); put("height", r.height)
                            put("sizeBytes", r.sizeBytes); put("frameCount", r.frameCount)
                        })
                    }
                    put("results", ra)
                }
                json.put(obj)
            }
            file.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    /** 【修复】应用重启时 RUNNING/PAUSED 任务标记为失败（无法真正恢复） */
    fun restoreTasks(context: Context) {
        // 必须始终监听 ExportService 状态，否则新建任务时进度条不会更新
        listenToExportState()

        val file = File(context.filesDir, "tasks.json")
        if (!file.exists()) return

        try {
            val json = org.json.JSONArray(file.readText())
            val restored = mutableListOf<ExportTask>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val results = mutableListOf<GifResult>()
                val ra = obj.getJSONArray("results")
                for (j in 0 until ra.length()) {
                    val r = ra.getJSONObject(j)
                    if (File(r.getString("filePath")).exists()) {
                        results.add(GifResult(r.getInt("index"), r.getString("filePath"),
                            r.getInt("width"), r.getInt("height"), r.getLong("sizeBytes"), r.getInt("frameCount")))
                    }
                }
                val status = TaskStatus.valueOf(obj.getString("status"))
                val actualStatus = when (status) {
                    TaskStatus.RUNNING, TaskStatus.PAUSED -> TaskStatus.FAILED
                    else -> status
                }
                val errMsg = if (actualStatus == TaskStatus.FAILED && status != TaskStatus.FAILED)
                    "应用意外退出，任务中断" else obj.optString("errorMessage", null)?.ifEmpty { null }

                restored.add(ExportTask(id = obj.getString("id"), createdAt = obj.getLong("createdAt"),
                    sourceLabel = obj.getString("sourceLabel"),
                    sourceType = if (obj.getString("sourceType") == "IMAGES") ExportTask.SourceType.IMAGES else ExportTask.SourceType.VIDEO,
                    segmentCount = obj.getInt("segmentCount"), gapRatio = obj.getDouble("gapRatio").toFloat(),
                    status = actualStatus, progress = obj.getInt("progress"), total = obj.getInt("total"),
                    results = results, errorMessage = errMsg,
                    config = GifConfig(outputWidth = obj.getInt("outputWidth"), outputHeight = obj.getInt("outputHeight"),
                        frameDelayMs = obj.getInt("frameDelayMs"), maxFrameRate = obj.optInt("maxFrameRate", 15), maxVideoFrames = obj.getInt("maxVideoFrames"),
                        loopForever = obj.getBoolean("loopForever"), colorCount = obj.optInt("colorCount", 256),
                        useBayerDither = obj.optBoolean("useBayerDither", false), losslessOptimize = obj.optBoolean("losslessOptimize", false),
                        paletteStatsFull = obj.optBoolean("paletteStatsFull", false),
                        transparencyOptimize = obj.optBoolean("transparencyOptimize", false),
                        frameDedup = obj.optBoolean("frameDedup", false)),
                    gifSourcePath = obj.optString("gifSourcePath", null)?.takeIf { it.isNotEmpty() }))
            }
            tasks = restored
        } catch (_: Exception) {}
    }

    /** 始终监听 ExportService 状态，保证进度条实时更新（不依赖 tasks.json 是否存在） */
    private fun listenToExportState() {
        viewModelScope.launch {
            com.example.gifapp.service.ExportService.state.collect { state ->
                when (state) {
                    is com.example.gifapp.service.ExportState.Progress -> {
                        tasks = tasks.map { if (it.id == state.taskId) it.copy(progress = state.current, total = state.total, status = TaskStatus.RUNNING) else it }
                    }
                    is com.example.gifapp.service.ExportState.Done -> {
                        tasks = tasks.map { if (it.id == state.taskId) it.copy(status = TaskStatus.COMPLETED, progress = state.results.size, total = state.results.size, results = state.results) else it }
                    }
                    is com.example.gifapp.service.ExportState.Failed -> {
                        tasks = tasks.map { if (it.id == state.taskId) it.copy(status = TaskStatus.FAILED, errorMessage = state.error) else it }
                    }
                    is com.example.gifapp.service.ExportState.Cancelled -> {
                        tasks = tasks.map { if (it.id == state.taskId) it.copy(status = TaskStatus.CANCELLED) else it }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun syncAndSave(context: Context) { tasks = tasks.toList(); saveTasks(context) }

    fun cleanupExportCache(context: Context) {
        for (t in tasks) if (t.isFinished) {
            val dir = File(context.getExternalFilesDir("GIFSplitter") ?: context.filesDir, t.id)
            if (dir.exists()) { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
        }
    }

    // ---- Import ----

    fun importMultipleImages(context: Context, uris: List<Uri>) {
        if (isImporting) return
        isImporting = true
        viewModelScope.launch {
            try {
                mediaSource = null; generatedGifs = emptyList()
                previewBitmap = null
                if (uris.isNotEmpty()) {
                    // 复制到缓存（避免 URI 权限过期问题）
                    val cachedUris = withContext(Dispatchers.IO) { copyUrisToCache(context, uris, "images") }
                    imageUris = cachedUris
                    val bm = withContext(Dispatchers.IO) { ImageUtils.loadBitmapFromUri(context, cachedUris[0]) }
                    if (bm != null) { previewBitmap = bm
                        mediaSource = MediaSource.Image(bm, cachedUris[0].toString())
                        currentImageIndex = 0; resetTransform(); resetConfigDimensions() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isImporting = false
            }
        }
    }

    /** 导入 GIF 动图（只存源文件+首帧预览，导出时再拆帧） */
    fun importGif(context: Context, uri: Uri) {
        if (isImporting) return
        isImporting = true
        viewModelScope.launch {
            mediaSource = null; generatedGifs = emptyList(); previewBitmap = null; imageUris = emptyList()
            try {
                val gifFilePath = withContext(Dispatchers.IO) {
                    val cacheDir = File(context.cacheDir, "gif_source").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    // 保存原始 GIF
                    val gifFile = File(cacheDir, "input.gif")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        gifFile.outputStream().use { input.copyTo(it) }
                    } ?: throw Exception("无法读取 GIF")
                    // 校验 GIF 魔数
                    val magic = gifFile.inputStream().use { it.readBytes().take(6).toByteArray() }
                    val magicStr = magic.toString(Charsets.US_ASCII)
                    if (magicStr != "GIF87a" && magicStr != "GIF89a") {
                        gifFile.delete(); throw Exception("请选择正确的文件")
                    }
                    gifFile.absolutePath
                }
                gifSourcePath = gifFilePath
                // 取第一帧做预览
                val frameDir = File(context.cacheDir, "gif_preview").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val previewCmd = "-y -i \"$gifFilePath\" -vsync 0 -vframes 1 \"${frameDir.absolutePath}/preview.png\""
                val session = try { com.arthenica.ffmpegkit.FFmpegKit.execute(previewCmd) }
                    catch (_: Throwable) { null }
                if (session != null && com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)) {
                    val previewFile = File(frameDir, "preview.png")
                    if (previewFile.exists()) {
                        val bm = withContext(Dispatchers.IO) { ImageUtils.loadBitmapFromUri(context, Uri.fromFile(previewFile)) }
                        if (bm != null) { previewBitmap = bm
                            mediaSource = MediaSource.Image(bm, previewFile.absolutePath)
                            currentImageIndex = 0; resetTransform(); resetConfigDimensions() }
                    }
                }
            } catch (e: Exception) {
                if (e.message == "请选择正确的文件") {
                    importErrorMessage = "请选择正确的文件"; showImportError = true
                }
                e.printStackTrace()
            } finally {
                isImporting = false
            }
        }
    }
    fun appendImages(context: Context, uris: List<Uri>) {
        if (isImporting || uris.isEmpty()) return
        isImporting = true
        viewModelScope.launch {
            try {
                val cachedNew = withContext(Dispatchers.IO) { copyUrisToCache(context, uris, "images", clearExisting = false) }
                val merged = (imageUris + cachedNew).distinct()
                imageUris = merged
                if (previewBitmap == null && merged.isNotEmpty()) {
                    val bm = withContext(Dispatchers.IO) { ImageUtils.loadBitmapFromUri(context, merged[0]) }
                    if (bm != null) { previewBitmap = bm
                        mediaSource = MediaSource.Image(bm, merged[0].toString())
                        currentImageIndex = 0; resetTransform(); resetConfigDimensions() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isImporting = false
            }
        }
    }

    /** 将 URI 内容复制到缓存目录，返回本地 cached Uri（不依赖临时权限） */
    private fun copyUrisToCache(context: Context, uris: List<Uri>, subDir: String, clearExisting: Boolean = true): List<Uri> {
        val dir = File(context.cacheDir, subDir).apply { mkdirs() }
        if (clearExisting) dir.listFiles()?.forEach { it.delete() }
        // 追加模式：从现有文件数之后开始编号，避免覆盖
        val startIdx = if (clearExisting) 0 else (dir.listFiles()?.filter { it.name.startsWith("img_") }?.size ?: 0)
        return uris.mapIndexed { i, uri ->
            val file = File(dir, "img_${"%03d".format(startIdx + i)}.png")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                Uri.fromFile(file)
            } catch (_: Exception) {
                Uri.EMPTY
            }
        }.filter { it != Uri.EMPTY }
    }

    fun switchImage(context: Context, index: Int) {
        if (index !in imageUris.indices || index == currentImageIndex) return
        viewModelScope.launch {
            val bm = withContext(Dispatchers.IO) { ImageUtils.loadBitmapFromUri(context, imageUris[index]) }
            if (bm != null) {
                previewBitmap = bm
                currentImageIndex = index
                mediaSource = MediaSource.Image(bm, imageUris[index].toString())
                resetTransform(); resetConfigDimensions()
            }
        }
    }

    fun importVideo(context: Context, uri: Uri) {
        if (isImporting) return
        isImporting = true
        viewModelScope.launch {
            try {
                mediaSource = null; generatedGifs = emptyList()
                previewBitmap = null
                cachedVideoFrames = emptyList()
                // 只取第一帧做预览，完整拆帧留给 FFmpeg 导出时处理
                val firstFrame = withContext(Dispatchers.IO) { ImageUtils.extractFirstVideoFrame(context, uri) }
                if (firstFrame != null) { previewBitmap = firstFrame
                    mediaSource = MediaSource.Video(uri.toString()); resetTransform(); resetConfigDimensions() }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isImporting = false
            }
        }
    }

    // ---- Editor ----

    fun updateSegmentCount(c: Int) { segmentCount = c.coerceIn(segmentCountRange); resetConfigDimensions() }
    fun updateGapRatio(r: Float) { gapRatio = r.coerceIn(gapRatioRange); resetConfigDimensions() }
    fun updateGifConfig(c: GifConfig) { gifConfig = c }
    fun updateTransform(s: Float, ox: Float, oy: Float) { scale = s.coerceIn(0.3f, 5f); offsetX = ox; offsetY = oy }
    fun resetTransform() { scale = 1f; offsetX = 0f; offsetY = 0f }
    private fun resetConfigDimensions() {
        if (segmentWidth > 0 && segmentHeight > 0) gifConfig = gifConfig.withWidth(gifConfig.outputWidth, segmentWidth, segmentHeight)
    }

    // ---- Export Task System 【全面修复】 ----

    fun startExport(context: Context) {
        if (segmentCount < 1 || previewBitmap == null) return
        saveConfig(context)

        // 构建任务（含 Service 需要的导出参数）
        val srcW = previewBitmap?.width ?: 0
        val srcH = previewBitmap?.height ?: 0
        val isGif = gifSourcePath != null
        val task = ExportTask(
            sourceLabel = when { isGif -> "GIF"; imageUris.size > 1 -> "${imageUris.size}张图片"; mediaSource is MediaSource.Video -> "视频"; else -> "图片" },
            sourceType = when (mediaSource) { is MediaSource.Video -> ExportTask.SourceType.VIDEO; else -> ExportTask.SourceType.IMAGES },
            segmentCount = segmentCount, gapRatio = gapRatio, config = gifConfig,
            status = TaskStatus.QUEUED, total = segmentCount,
            sourceWidth = srcW, sourceHeight = srcH,
            videoUri = (mediaSource as? MediaSource.Video)?.uri,
            imageUris = if (isGif) emptyList() else imageUris.map { it.toString() },
            gifSourcePath = gifSourcePath
        )
        tasks = listOf(task) + tasks
        showTaskPanel = true

        // 保存到文件（Service 会读取）
        saveTasks(context)

        // 启动前台 Service（只传 taskId，避免 Intent 溢出）
        val intent = Intent(context, com.example.gifapp.service.ExportService::class.java).apply {
            putExtra("taskId", task.id)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    /** 取消任务：通知 Service 中止 */
    fun cancelTask(taskId: String, context: Context) {
        com.example.gifapp.service.ExportService.cancelExport(context, taskId)
        tasks = tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.CANCELLED) else it }
        saveTasks(context)
    }

    /** 【修复】复用 cancel 逻辑，并清理缓存文件 */
    fun removeTask(taskId: String, context: Context) {
        cancelTask(taskId, context)
        cleanupTask(taskId, context)
        tasks = tasks.filter { it.id != taskId }
        saveTasks(context)
    }

    fun clearFinished(context: Context) {
        tasks.filter { it.isFinished }.forEach { cleanupTask(it.id, context) }
        tasks = tasks.filter { !it.isFinished }
        saveTasks(context)
    }

    fun toggleTaskPanel() { showTaskPanel = !showTaskPanel }

    private fun cleanupTask(taskId: String, context: Context) {
        val dir = File(context.getExternalFilesDir("GIFSplitter") ?: context.filesDir, taskId)
        if (dir.exists()) { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    // ---- Save to Gallery ----

    fun saveTaskToGallery(taskId: String, context: Context) {
        val task = tasks.find { it.id == taskId } ?: return
        if (task.results.isEmpty()) return
        viewModelScope.launch {
            val dirName = "GIFSplitter/${java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}"
            withContext(Dispatchers.IO) {
                val baseTime = System.currentTimeMillis() / 1000
                task.results.sortedBy { it.index }.forEachIndexed { i, gif ->
                    val file = File(gif.filePath)
                    if (!file.exists()) return@forEachIndexed
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "segment_${gif.index + 1}.gif")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$dirName")
                        // 用 baseTime + i 保证时间戳顺序与段序号一致
                        put(MediaStore.Images.Media.DATE_ADDED, baseTime)
                        put(MediaStore.Images.Media.DATE_MODIFIED, baseTime + i)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                    }
                }
            }
            lastSavedDirName = dirName
        }
    }

    fun clearAll() {
        mediaSource = null; imageUris = emptyList(); currentImageIndex = 0
        previewBitmap = null; gifSourcePath = null; showImportError = false; importErrorMessage = ""
        cachedVideoFrames.forEach { it.recycle() }; cachedVideoFrames = emptyList()
        generatedGifs = emptyList(); lastSavedDirName = null; resetTransform()
    }

    override fun onCleared() {
        super.onCleared()
        previewBitmap = null
    }
}
