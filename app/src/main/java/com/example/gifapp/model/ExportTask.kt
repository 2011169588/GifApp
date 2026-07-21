package com.example.gifapp.model

import android.graphics.Bitmap

/**
 * Status of an export task.
 */
enum class TaskStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED
}

/**
 * Represents a GIF export task with full lifecycle control.
 */
data class ExportTask(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val createdAt: Long = System.currentTimeMillis(),
    val sourceType: SourceType = SourceType.IMAGES,
    val sourceLabel: String = "",
    val segmentCount: Int = 3,
    val gapRatio: Float = 0.03f,
    val config: GifConfig = GifConfig(),
    var status: TaskStatus = TaskStatus.QUEUED,
    var progress: Int = 0,
    var total: Int = 1,
    var results: List<GifResult> = emptyList(),
    var errorMessage: String? = null,
    // 导出参数（运行时填充，Service 读取）
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val videoUri: String? = null,
    val imageUris: List<String> = emptyList()
) {
    enum class SourceType { IMAGES, VIDEO }

    val isActive: Boolean get() = status == TaskStatus.RUNNING || status == TaskStatus.PAUSED
    val isFinished: Boolean get() = status in listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.FAILED)
}

/**
 * Result of a single GIF generation.
 */
data class GifResult(
    val index: Int,
    val filePath: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val frameCount: Int
)
