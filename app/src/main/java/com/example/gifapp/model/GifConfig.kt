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
    val parallelCount: Int = 1
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
