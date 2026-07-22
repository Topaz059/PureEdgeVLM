package com.topaz.pureedgevlm

import android.content.Context
import android.content.res.AssetManager
import java.io.File

// 大模型生成时的逐字回调（C++ 每解出一个片段就调一次 onToken）
// 参数是原始 UTF-8 字节（C++ 已保证在完整字符边界切分）：
// 用字节而非 String 是因为 JNI 的 NewStringUTF 不认 emoji 等 4 字节字符会崩，
// 这里由 Kotlin 侧用 String(bytes, UTF-8) 自行解码。
interface LlmCallback {
    fun onToken(bytes: ByteArray)
}

// 连接 C++ 的桥。object 单例，加载一次 native 库。
object NativeBridge {
    init {
        System.loadLibrary("pureedgevlm")
    }

    // 初始化：把 AssetManager 交给 C++，C++ 会去 assets/models 下读模型
    external fun nativeInit(assetManager: AssetManager)
    // YOLO 检测：传 Bitmap，返回检测框数组
    external fun yoloDetect(bitmap: android.graphics.Bitmap, conf: Float, nms: Float): Array<YoloBox>
    // 场景识别：传 Bitmap，返回前 5 个场景（编号 + 概率）
    external fun sceneRecognize(bitmap: android.graphics.Bitmap): Array<SceneResult>
    // OCR 文字识别：传 Bitmap，返回识别出的文字（多行文本框换行拼接）
    external fun ocrRecognize(bitmap: android.graphics.Bitmap): String
    // 调试信息：返回最近一次检测的加载状态 / 最高分 / 框数
    external fun getDebug(): String

    // ===== 阶段四大模型接口 =====
    // 从绝对路径加载 GGUF 模型，成功返回 true
    external fun llmLoad(path: String): Boolean
    // 生成：把 Kotlin 拼好的「完整 ChatML 多轮对话」prompt 喂给模型，
    // 每出一个片段通过 callback.onToken 回传（打字机效果）
    external fun llmGenerate(prompt: String, maxTokens: Int, callback: LlmCallback)

    // ===== 阶段五 Benchmark =====
    // 对四个模型按线程 1/2/4/8 各跑 iterations 次测速，结果写进 csvPath（CSV 文件）。
    // 返回摘要文本（含 CSV 路径）。视觉三模型 + 大模型（若已加载）都会测。
    external fun benchmarkRun(bitmap: android.graphics.Bitmap, csvPath: String, iterations: Int): String

    // 场景标签：365 行，每行格式 "场景名 编号"，读 assets 里的 categories_places365.txt
    // 第 index 行（从 0 开始）就是编号 index 对应的场景名
    var sceneLabels: List<String> = emptyList()
        private set

    fun init(context: Context) {
        nativeInit(context.assets)
        sceneLabels = loadSceneLabels(context)
    }

    // 首次使用时把 assets 里的大模型拷到内部存储再加载（分块流式，避免一次读 0.5GB 爆内存）。
    // 之后复用缓存，不重复拷。返回是否加载成功。
    private const val LLM_ASSET = "models/llm/MiniCPM5-1B-Q4_K_M.gguf"
    private const val LLM_FILE  = "MiniCPM5-1B-Q4_K_M.gguf"
    @Volatile private var llmReady = false

    fun ensureLlmLoaded(context: Context): Boolean {
        if (llmReady) return true
        val dir = File(context.filesDir, "models/llm")
        dir.mkdirs()
        val out = File(dir, LLM_FILE)
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(LLM_ASSET).use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(1024 * 1024) // 1MB 一块
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                    }
                }
            }
        }
        llmReady = llmLoad(out.absolutePath)
        return llmReady
    }

    // 从 assets 读场景标签文件，按行号存成列表
    private fun loadSceneLabels(context: Context): List<String> {
        val list = mutableListOf<String>()
        context.assets.open("models/scene/categories_places365.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val name = line.trim().substringBefore(' ')
                if (name.isNotEmpty()) list.add(name)
            }
        }
        return list
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
