package com.example.gifapp.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.gifapp.model.MediaSource
import com.example.gifapp.ui.components.ExportSettingsDialog
import com.example.gifapp.ui.components.ImageCanvas
import com.example.gifapp.ui.components.SplitControls
import com.example.gifapp.ui.components.TaskPanel
import com.example.gifapp.viewmodel.EditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val mediaSource = viewModel.mediaSource
    val segmentCount = viewModel.segmentCount
    val gapRatio = viewModel.gapRatio
    val scale = viewModel.scale
    val gifConfig = viewModel.gifConfig
    val imageUris = viewModel.imageUris
    val tasks = viewModel.tasks
    val showTaskPanel = viewModel.showTaskPanel

    var showClearConfirm by remember { mutableStateOf(false) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showExportSettings by remember { mutableStateOf(false) }
    var announcements by remember { mutableStateOf(emptyList<com.example.gifapp.model.Announcement>()) }
    var currentAnnouncementIndex by remember { mutableIntStateOf(0) }

    // 通知权限请求（Android 13+）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }
    fun ensureNotificationPermission(block: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                block()
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                block() // export 照常进行（没通知权限只是没通知栏提示）
            }
        } else {
            block()
        }
    }

    val multiImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) viewModel.importMultipleImages(context, uris)
    }
    val appendImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) viewModel.appendImages(context, uris)
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importGif(context, uri)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importVideo(context, it) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadConfig(context)
        viewModel.restoreTasks(context)
        viewModel.checkCrashMarker(context)
        announcements = withContext(Dispatchers.IO) { com.example.gifapp.util.AnnouncementManager.fetchUnread(context) }
    }

    fun dismissAnnouncement() {
        if (announcements.isNotEmpty()) {
            com.example.gifapp.util.AnnouncementManager.markSeen(context, announcements[currentAnnouncementIndex].id)
            if (currentAnnouncementIndex < announcements.size - 1) {
                currentAnnouncementIndex++
            } else {
                announcements = emptyList()
            }
        }
    }

    val sourceBitmap = when (mediaSource) {
        is MediaSource.Image -> mediaSource.bitmap
        is MediaSource.Video -> viewModel.currentPreviewBitmap
        else -> null
    }

    val hasActiveTasks = tasks.any { it.status == com.example.gifapp.model.TaskStatus.RUNNING }
    val importing = viewModel.isImporting

    // Import loading dialog
    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("正在导入", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                    Text("正在加载素材…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("文件较大时可能需要几秒钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            },
            confirmButton = {}
        )
    }

    // Back button returns to home (clear media source)
    if (mediaSource != null) {
        BackHandler { viewModel.clearAll() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GIF 分割", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (mediaSource != null) Text(
                            buildString {
                                if (imageUris.size > 1) append("${imageUris.size}张 · ")
                                append("${sourceBitmap?.width}×${sourceBitmap?.height}")
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleTaskPanel() }) {
                        BadgedBox(badge = { if (hasActiveTasks) Badge() }) {
                            Icon(Icons.Outlined.ListAlt, "任务列表")
                        }
                    }
                    if (mediaSource != null) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Outlined.Refresh, "重新开始")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (mediaSource != null && sourceBitmap != null) {
                // Editor mode
                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // Canvas
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ImageCanvas(
                            bitmap = sourceBitmap, segmentCount = segmentCount, gapRatio = gapRatio,
                            scale = scale, offsetX = viewModel.offsetX, offsetY = viewModel.offsetY,
                            onTransformChanged = { s, ox, oy -> viewModel.updateTransform(s, ox, oy) }
                        )

                        // Zoom badge
                        if (scale != 1f) {
                            Surface(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f)) {
                                Text("${(scale * 100).toInt()}%",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Info badge
                        Surface(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                            shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f)) {
                            Text("${segmentCount}段 · 间隔${(gapRatio * 100).toInt()}%",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Image navigation
                    if (imageUris.size > 1) {
                        val curIdx = viewModel.currentImageIndex
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.switchImage(context, curIdx - 1) },
                                enabled = curIdx > 0) { Icon(Icons.Default.ChevronLeft, "上一张") }
                            Text("${curIdx + 1} / ${imageUris.size}", style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { viewModel.switchImage(context, curIdx + 1) },
                                enabled = curIdx < imageUris.size - 1) { Icon(Icons.Default.ChevronRight, "下一张") }
                        }
                    }

                    // Controls
                    SplitControls(segmentCount = segmentCount, gapRatio = gapRatio,
                        onSegmentCountChange = { viewModel.updateSegmentCount(it) },
                        onGapRatioChange = { viewModel.updateGapRatio(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

                    // Export button
                    Button(onClick = { showExportSettings = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导出设置 · $segmentCount 段", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // FAB (仅图片/动图模式显示)
                if (mediaSource !is com.example.gifapp.model.MediaSource.Video) {
                    Column(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimatedVisibility(visible = showImportMenu,
                            enter = fadeIn() + slideInVertically { it / 2 },
                            exit = fadeOut() + slideOutVertically { it / 2 }) {
                            SmallFloatingActionButton(onClick = { showImportMenu = false; appendImagePicker.launch("image/*") },
                                containerColor = if (importing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer) {
                                Icon(Icons.Default.NoteAdd, "追加图片")
                            }
                        }
                        FloatingActionButton(onClick = { showImportMenu = !showImportMenu },
                            containerColor = if (importing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary) {
                            Icon(if (showImportMenu) Icons.Default.Close else Icons.Default.Add, "导入")
                        }
                    }
                }

            } else {
                // Empty state with polished design
                EmptyImportState(
                    onPickMultiple = { multiImagePicker.launch("image/*") },
                    onPickGif = { gifPicker.launch("image/gif") },
                    onPickVideo = { videoPicker.launch("video/*") },
                    enabled = !importing,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // Dialogs & panels
    if (showExportSettings) {
        ExportSettingsDialog(config = gifConfig, sourceWidth = sourceBitmap?.width ?: 0,
            sourceHeight = sourceBitmap?.height ?: 0, segmentCount = segmentCount, gapRatio = gapRatio,
            isVideo = viewModel.isVideoMode,
            onConfigChange = { viewModel.updateGifConfig(it) },
            onConfirm = { showExportSettings = false; ensureNotificationPermission { viewModel.startExport(context) } },
            onDismiss = { showExportSettings = false })
    }

    if (showTaskPanel) {
        TaskPanel(tasks = tasks,
            onCancel = { viewModel.cancelTask(it, context) },
            onRemove = { viewModel.removeTask(it, context) },
            onSaveToGallery = { viewModel.saveTaskToGallery(it, context) },
            onClearFinished = { viewModel.clearFinished(context) },
            onDismiss = { viewModel.toggleTaskPanel() })
    }

    if (showClearConfirm) {
        AlertDialog(onDismissRequest = { showClearConfirm = false },
            title = { Text("重新开始") },
            text = { Text("当前编辑的内容将被清除，确定吗？") },
            confirmButton = { TextButton(onClick = { showClearConfirm = false; viewModel.clearAll() }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } })
    }

    // 崩溃恢复提示
    if (viewModel.showCrashWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCrashWarning() },
            title = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error); Text("上次导出异常中断") },
            text = { Text("导出过程中应用意外退出，可能是内存不足导致。\n\n建议在导出设置中适当减少「并行合成数」，或关闭其他后台应用后再试。") },
            confirmButton = { TextButton(onClick = { viewModel.dismissCrashWarning() }) { Text("知道了") } }
        )
    }

    // 公告弹窗
    if (announcements.isNotEmpty()) {
        val a = announcements[currentAnnouncementIndex]
        AlertDialog(
            onDismissRequest = { dismissAnnouncement() },
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (a.type == "update") {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Text(a.title, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                    if (announcements.size > 1) {
                        Text("${currentAnnouncementIndex + 1} / ${announcements.size}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dismissAnnouncement() }) { Text(if (currentAnnouncementIndex == announcements.lastIndex) "知道了" else "下一条 →") } }
        )
    }

    // 并行数超安全值确认
    if (viewModel.showParallelConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissParallelConfirm() },
            title = { Text("并行数超过建议值") },
            text = { Text("当前设备建议最大并行数为 ${viewModel.safeParallelCount}，超过可能会因内存不足导致导出崩溃。\n\n确定要继续吗？") },
            confirmButton = { TextButton(onClick = { viewModel.confirmParallel() }) { Text("继续", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissParallelConfirm(); viewModel.updateGifConfig(viewModel.gifConfig.copy(parallelCount = viewModel.safeParallelCount)) }) { Text("改为 ${viewModel.safeParallelCount}") } }
        )
    }
}

@Composable
private fun EmptyImportState(onPickMultiple: () -> Unit, onPickGif: () -> Unit, onPickVideo: () -> Unit, enabled: Boolean = true, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))

        // Logo
        Surface(modifier = Modifier.size(112.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Gif, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("合成并切分 GIF", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Text("长图分段、动图拆帧或视频转 GIF，\n每段独立保存，自由分享",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }

        Spacer(Modifier.height(40.dp))

        // Import cards
        Column(modifier = Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ImportCard(
                icon = Icons.Outlined.Collections, title = "导入多张图片",
                subtitle = "每张图片作为一帧，合成动画 GIF",
                onClick = onPickMultiple, enabled = enabled
            )
            ImportCard(
                icon = Icons.Outlined.Image, title = "导入 GIF 动图",
                subtitle = "拆帧后分段切割，逐段导出",
                onClick = onPickGif, enabled = enabled, isOutline = true
            )
            ImportCard(
                icon = Icons.Outlined.VideoFile, title = "导入视频",
                subtitle = "提取视频帧，生成动效 GIF",
                onClick = onPickVideo, enabled = enabled, isOutline = true
            )
        }

        Spacer(Modifier.weight(1f))

        // Footer
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("① 导入素材", "② 设置分割", "③ 导出分享").forEach { s ->
                Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ImportCard(icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true, isOutline: Boolean = false
) {
    if (isOutline) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(14.dp), enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    } else {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(14.dp), enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f))
            }
        }
    }
}
