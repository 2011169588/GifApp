package com.example.gifapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gifapp.ui.theme.SegmentColors

@Composable
fun SplitControls(
    segmentCount: Int,
    gapRatio: Float,
    onSegmentCountChange: (Int) -> Unit,
    onGapRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Segment count
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (segmentCount == 1) "切割模式" else "切割段数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(if (segmentCount == 1) "不分割" else "$segmentCount", modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Slider(value = segmentCount.toFloat(), onValueChange = { onSegmentCountChange(it.toInt()) },
                    valueRange = 1f..20f, steps = 18)
            }

            // Gap (hidden when no split)
            if (segmentCount > 1) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.ViewDay, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            Text("段间隔", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Text("${(gapRatio * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    }
                    Slider(value = gapRatio, onValueChange = { onGapRatioChange(it) }, valueRange = 0f..0.3f,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.secondary, activeTrackColor = MaterialTheme.colorScheme.secondary))
                    Text("丢弃间隙图像，减少 QQ 消息间隔的拉伸感",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            // Segment preview bar
            val showCount = minOf(segmentCount, 10)
            Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until showCount) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(SegmentColors[i % SegmentColors.size]))
                }
            }
        }
    }
}
