package com.topaz.pureedgevlm

// YOLO 检测框（C++ 那边填好，原图像素坐标）
data class YoloBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val label: Int,
    val score: Float
)
