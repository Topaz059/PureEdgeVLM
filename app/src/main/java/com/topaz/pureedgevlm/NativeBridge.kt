package com.topaz.pureedgevlm

import android.content.Context
import android.content.res.AssetManager

// 连接 C++ 的桥。object 单例，加载一次 native 库。
object NativeBridge {
    init {
        System.loadLibrary("pureedgevlm")
    }

    // 初始化：把 AssetManager 交给 C++，C++ 会去 assets/models 下读模型
    external fun nativeInit(assetManager: AssetManager)
    // YOLO 检测：传 Bitmap，返回检测框数组
    external fun yoloDetect(bitmap: android.graphics.Bitmap, conf: Float, nms: Float): Array<YoloBox>
    // 调试信息：返回最近一次检测的加载状态 / 最高分 / 框数
    external fun getDebug(): String

    fun init(context: Context) {
        nativeInit(context.assets)
    }

    // COCO 80 类名称（YOLO 输出的编号对应这里）
    val cocoLabels = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat",
        "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
        "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
        "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv",
        "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
}
