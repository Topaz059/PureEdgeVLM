package com.topaz.pureedgevlm

// 场景识别结果（C++ 那边填好）：类别编号 + 概率
data class SceneResult(
    val index: Int,
    val prob: Float
)
