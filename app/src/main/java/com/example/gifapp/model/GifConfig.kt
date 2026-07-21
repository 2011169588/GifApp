package com.example.gifapp.model

data class GifConfig(
    val outputWidth: Int = 480,
    val outputHeight: Int = 800,
    val frameDelayMs: Int = 100,
    val maxFrameRate: Int = 15,
    val maxVideoFrames: Int = 20,
    val loopForever: Boolean = true,
    val colorCount: Int = 256,
    val useBayerDither: Boolean = false,
    val losslessOptimize: Boolean = true,
    val parallelCount: Int = 1,
    // ——— 高级压缩选项 ———
    /** true = palettegen stats_mode=full(全局最优), false = stats_mode=diff(帧差优化) */
    val paletteStatsFull: Boolean = false,
    /** 启用 FFmpeg 透明色优化 (-gifflags -transparency) */
    val transparencyOptimize: Boolean = false,
    /** 删除连续相同帧，加倍前一帧显示时间 */
    val frameDedup: Boolean = false
) {
    fun withWidth(width: Int, segW: Int, segH: Int): GifConfig {
        val ratio = segH.toFloat() / segW.toFloat()
        return copy(outputWidth = width, outputHeight = (width * ratio).toInt())
    }
    fun withHeight(height: Int, segW: Int, segH: Int): GifConfig {
        val ratio = segW.toFloat() / segH.toFloat()
        return copy(outputHeight = height, outputWidth = (height * ratio).toInt())
    }
}
