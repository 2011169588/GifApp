package com.example.gifapp.model

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Configuration for splitting an image into multiple GIF segments.
 */
data class SplitConfig(
    /** Total number of segments to split into */
    val segmentCount: Int = 3,
    /** Gap ratio (0.0 - 0.5) — fraction of each segment height to discard between segments */
    val gapRatio: Float = 0.03f,
    /** Width of each segment in pixels (derived from source) */
    val segmentWidth: Int = 0,
    /** Height of each segment in pixels (computed) */
    val segmentHeight: Int = 0,
    /** Individual segment adjustments: null means use equal split with gapRatio */
    val segmentAdjustments: List<SegmentAdjustment>? = null
)

data class SegmentAdjustment(
    /** Custom height ratio for this segment relative to total usable height (0-1) */
    val heightRatio: Float = 0f,
    /** Offset from the start of usable area (0-1) */
    val offsetRatio: Float = 0f
)

/**
 * A single segment's computed bounds and render info.
 */
data class SegmentInfo(
    val index: Int,
    val sourceRect: RectF,
    val label: String,
    val color: Long
)

/**
 * Source media type for import.
 */
sealed class MediaSource {
    data class Image(val bitmap: Bitmap, val uri: String) : MediaSource()
    data class Video(val uri: String) : MediaSource()
}
