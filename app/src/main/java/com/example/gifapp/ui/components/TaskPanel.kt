package com.example.gifapp.ui.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.gifapp.model.ExportTask
import com.example.gifapp.model.TaskStatus
import com.example.gifapp.ui.theme.SegmentColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPanel(
    tasks: List<ExportTask>,
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSaveToGallery: (String) -> Unit,
    onClearFinished: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ListAlt, null, tint = MaterialTheme.colorScheme.primary)
                    Text("导出任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (tasks.any { it.isFinished }) {
                    var showClearConfirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清除完成")
                    }
                    if (showClearConfirm) {
                        AlertDialog(
                            onDismissRequest = { showClearConfirm = false },
                            title = { Text("清除所有已完成任务") },
                            text = { Text("确定清除所有已完成/已取消的任务？已保存到相册的不受影响。") },
                            confirmButton = { TextButton(onClick = { showClearConfirm = false; onClearFinished() }) { Text("确定") } },
                            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
                        )
                    }
                }
            }

            if (tasks.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Outbox,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                    Text(
                        "暂无导出任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 500.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onPause = { onPause(task.id) },
                            onResume = { onResume(task.id) },
                            onCancel = { onCancel(task.id) },
                            onRemove = { onRemove(task.id) },
                            onSaveToGallery = { onSaveToGallery(task.id) },
                            context = androidx.compose.ui.platform.LocalContext.current
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: ExportTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onSaveToGallery: () -> Unit,
    context: Context
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSavedBanner by remember { mutableStateOf(false) }
    val statusColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
            TaskStatus.PAUSED -> MaterialTheme.colorScheme.secondary
            TaskStatus.COMPLETED -> Color(0xFF22C55E)
            TaskStatus.CANCELLED -> MaterialTheme.colorScheme.error
            TaskStatus.FAILED -> Color(0xFFEF4444)
            TaskStatus.QUEUED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        },
        label = "statusColor"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Task header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor)
                    )
                    Text(
                        task.sourceLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    task.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Config info
            val frameInfo = if (task.sourceType == com.example.gifapp.model.ExportTask.SourceType.VIDEO || task.gifSourcePath != null) {
                "${task.config.maxFrameRate}fps"
            } else {
                "${task.config.frameDelayMs}ms/帧"
            }
            Text(
                "${task.segmentCount}段 · ${task.config.outputWidth}×${task.config.outputHeight} · $frameInfo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(8.dp))

            when (task.status) {
                TaskStatus.RUNNING, TaskStatus.QUEUED -> {
                    // Progress bar
                    LinearProgressIndicator(
                        progress = { if (task.total > 0) task.progress.toFloat() / task.total.toFloat() else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.12f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "处理中 ${task.progress}/${task.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        if (task.total > 0) {
                            Text(
                                "${(task.progress.toFloat() / task.total * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor
                            )
                        }
                    }
                }
                TaskStatus.PAUSED -> {
                    Divider()
                    Text(
                        "已暂停 — ${task.progress}/${task.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                TaskStatus.COMPLETED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.CheckCircle, null,
                            tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                        Text(
                            "完成 · ${task.results.size}个GIF · 共${formatTotalSize(task.results.sumOf { it.sizeBytes })}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF22C55E)
                        )
                    }
                }
                TaskStatus.CANCELLED -> {
                    Text(
                        "已取消 · ${task.progress}/${task.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TaskStatus.FAILED -> {
                    Text(
                        "失败: ${task.errorMessage ?: "未知错误"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    TaskStatus.RUNNING -> {
                        SmallButton(
                            onClick = onPause,
                            icon = Icons.Default.Pause,
                            label = "暂停",
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton(
                            onClick = onCancel,
                            icon = Icons.Default.Close,
                            label = "取消",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TaskStatus.PAUSED -> {
                        SmallButton(
                            onClick = onResume,
                            icon = Icons.Default.PlayArrow,
                            label = "继续",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton(
                            onClick = onCancel,
                            icon = Icons.Default.Close,
                            label = "取消",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TaskStatus.COMPLETED -> {
                        SmallButton(
                            onClick = { onSaveToGallery(); showSavedBanner = true },
                            icon = Icons.Default.Save,
                            label = "保存",
                            color = Color(0xFF22C55E)
                        )
                        Spacer(Modifier.width(8.dp))
                        SmallButton(
                            onClick = { showDeleteConfirm = true },
                            icon = Icons.Default.Delete,
                            label = "删除",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    TaskStatus.CANCELLED, TaskStatus.FAILED -> {
                        SmallButton(
                            onClick = { showDeleteConfirm = true },
                            icon = Icons.Default.Delete,
                            label = "清除",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    TaskStatus.QUEUED -> { /* no actions */ }
                }
            }

            // 保存成功提示
            if (showSavedBanner) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF22C55E).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                        Text("已保存到相册", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF22C55E))
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(if (task.status == TaskStatus.COMPLETED) "删除任务" else "清除任务") },
            text = { Text(if (task.status == TaskStatus.COMPLETED) "已保存到相册的 GIF 不受影响，确定删除？" else "确定清除这个任务吗？") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onRemove() }) { Text("确定", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SmallButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatTotalSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
