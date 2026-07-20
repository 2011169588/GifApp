package com.example.gifapp.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.gifapp.model.GifConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsDialog(
    config: GifConfig, sourceWidth: Int, sourceHeight: Int,
    segmentCount: Int, gapRatio: Float, isVideo: Boolean,
    onConfigChange: (GifConfig) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    val usableHeight = sourceHeight.toFloat() * (1f - gapRatio)
    val segH = if (segmentCount > 1) usableHeight / segmentCount else sourceHeight.toFloat()
    val segWidth = sourceWidth; val segHeight = segH.toInt()

    var widthText by remember(config) { mutableStateOf(config.outputWidth.toString()) }
    var heightText by remember(config) { mutableStateOf(config.outputHeight.toString()) }
    var delayText by remember(config) { mutableStateOf(config.frameDelayMs.toString()) }
    var fpsText by remember(config) { mutableStateOf(config.maxFrameRate.toString()) }
    var lockRatio by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("导出设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${sourceWidth}×${sourceHeight} · ${segmentCount}段", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                FilledTonalButton(onClick = onConfirm, shape = RoundedCornerShape(12.dp)) {
                    Text("开始导出", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // === 分辨率 ===
                CollapsibleSection(title = "输出分辨率", defaultOpen = true) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = widthText, onValueChange = { text ->
                            widthText = text; text.toIntOrNull()?.let { w ->
                                if (lockRatio) { val h = (w.toFloat() * segHeight / segWidth).toInt().coerceIn(1, 9999); heightText = h.toString(); onConfigChange(config.withWidth(w, segWidth, segHeight)) }
                                else onConfigChange(config.copy(outputWidth = w))
                            }
                        }, label = { Text("宽") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                        Text("×", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        OutlinedTextField(value = heightText, onValueChange = { text ->
                            heightText = text; text.toIntOrNull()?.let { h ->
                                if (lockRatio) { val w = (h.toFloat() * segWidth / segHeight).toInt().coerceIn(1, 9999); widthText = w.toString(); onConfigChange(config.withHeight(h, segWidth, segHeight)) }
                                else onConfigChange(config.copy(outputHeight = h))
                            }
                        }, label = { Text("高") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(checked = lockRatio, onCheckedChange = { lockRatio = it }, modifier = Modifier.size(24.dp))
                        Text("锁定比例 ${(segWidth.toFloat() / segHeight * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                // === 帧控制 ===
                CollapsibleSection(title = if (!isVideo) "帧显示时间" else "帧率限制", defaultOpen = false) {
                    if (!isVideo) {
                        OutlinedTextField(value = delayText, onValueChange = { delayText = it; it.toIntOrNull()?.let { ms -> onConfigChange(config.copy(frameDelayMs = ms.coerceIn(10, 10000))) } },
                            label = { Text("每帧显示") }, suffix = { Text("ms") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("数值越大播放越慢", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    } else {
                        OutlinedTextField(value = fpsText, onValueChange = { fpsText = it; it.toIntOrNull()?.let { fps -> onConfigChange(config.copy(maxFrameRate = fps.coerceIn(1, 60))) } },
                            label = { Text("最大帧率") }, suffix = { Text("fps") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("越高越流畅但文件越大", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }

                // === 压缩 ===
                CollapsibleSection(title = "压缩", defaultOpen = true) {
                    // 色彩数
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("色彩数", style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("${config.colorCount}色", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Slider(value = config.colorCount.toFloat(), onValueChange = { onConfigChange(config.copy(colorCount = it.toInt())) },
                        valueRange = 32f..256f, steps = 6)
                    // 抖动
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("抖动算法", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !config.useBayerDither, onClick = { onConfigChange(config.copy(useBayerDither = false)) },
                                label = { Text("Floyd") }, leadingIcon = if (!config.useBayerDither) {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null)
                            FilterChip(selected = config.useBayerDither, onClick = { onConfigChange(config.copy(useBayerDither = true)) },
                                label = { Text("Bayer") }, leadingIcon = if (config.useBayerDither) {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null)
                        }
                    }
                    // 无损优化
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("无损优化", style = MaterialTheme.typography.bodyMedium)
                            Text("帧差算法，只存变化部分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                        Switch(checked = config.losslessOptimize, onCheckedChange = { onConfigChange(config.copy(losslessOptimize = it)) })
                    }
                }

                // === 并行 ===
                CollapsibleSection(title = "并行合成", defaultOpen = false) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("同时合成 ${config.parallelCount} 段", style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                when { config.parallelCount <= 1 -> "省内存"; config.parallelCount <= 3 -> "均衡"; else -> "极速" },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Slider(value = config.parallelCount.toFloat(), onValueChange = { onConfigChange(config.copy(parallelCount = it.toInt())) }, valueRange = 1f..6f, steps = 4)
                    Text("越高越快但越耗内存，若闪退会自动调低", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }

                // 底部提示
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                    Text("每段 ${widthText}×${heightText} · $segmentCount 段 · ${if (isVideo) "${config.maxFrameRate}fps" else "${config.frameDelayMs}ms/帧"}",
                        modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CollapsibleSection(
    title: String, defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultOpen) }

    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}
