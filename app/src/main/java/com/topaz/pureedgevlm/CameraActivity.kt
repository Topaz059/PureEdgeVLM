package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

// 阶段五：相机实时检测页。
// 做法：用 PreviewView 显示相机原生预览（流畅、竖屏、方向正确），
// OverlayView 在它上面画框。两者都用 FIT_CENTER 居中缩放。
// 之前框偏的根因：① 预览是相机原生 16:9，分析帧是 4:3（640x480），比例不同 → 缩放后位置错开；
//               ② 分析帧没转正，竖屏手机上变成横的。
// 本版修复：① 预览也设成 4:3（640x480），与分析帧比例一致；
//          ② 分析帧按 rotationDegrees 手动转正成竖屏，再送检测，框坐标就是竖屏坐标。
// 这样预览(竖屏4:3)和框(竖屏4:3)用同一套缩放 → 框必然压在物体上，且相机依旧流畅。
@SuppressLint("SetTextI18n")
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var tvInfo: TextView
    private lateinit var btnBack: Button

    // 上一帧还在推理时，直接丢弃新帧，避免堆积卡死
    @Volatile private var busy = false
    private val TAG = "CameraPage" // logcat 搜索关键词，看相机页运行日志
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    // 运行时申请相机权限
    private val requestPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "camera permission granted=$granted")
        if (granted) startCamera()
        else tvInfo.text = "⚠️ 未授予相机权限，无法使用实时检测。点「返回」回到主界面。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        overlay = OverlayView(this)
        tvInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "正在启动相机…"
            setBackgroundColor(0x80000000.toInt()) // 半透明黑底，文字看得清
            setPadding(24, 24, 24, 24)
        }
        btnBack = Button(this).apply { text = "← 返回" }
        btnBack.setOnClickListener { finish() }

        // 用 FrameLayout 把预览、画框层、文字、按钮叠在一起
        val frame = FrameLayout(this)
        frame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val infoLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(24, 24, 24, 24)
        }
        frame.addView(tvInfo, infoLp)
        val backLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(24, 24, 24, 24)
        }
        frame.addView(btnBack, backLp)
        setContentView(frame)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(640, 480)) // 4:3，与分析帧比例一致，框才对齐
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480)) // 4:3
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
                tvInfo.text = "实时检测中…（YOLO 框 + 场景）"
                Log.d(TAG, "camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败", e)
                tvInfo.text = "⚠️ 相机启动失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 每来一帧都会进这里（在 analyzerExecutor 线程）
    private val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (busy) { imageProxy.close(); return@Analyzer } // 上一帧还没跑完，丢弃这一帧
        busy = true
        val raw = imageProxy.toBitmap() // YUV(相机) → Bitmap(RGB)，未旋转（竖屏手机上会横着）
        // 按相机旋转角把帧转正成竖屏，再送检测，框坐标才是竖屏坐标
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = if (rotation == 0) raw else rotateBitmap(raw, rotation)
        if (bitmap != raw) raw.recycle()
        val bw = bitmap.width
        val bh = bitmap.height
        imageProxy.close() // 必须关，否则相机流会卡住
        inferenceExecutor.execute {
            try {
                val argb = toArgb(bitmap)
                // 相机实时用较低置信度阈值，避免漏检（和视觉管线一致用 0.2）
                val boxes = NativeBridge.yoloDetect(argb, 0.2f, 0.45f)
                val scenes = NativeBridge.sceneRecognize(argb)
                if (argb != bitmap) argb.recycle()
                val sceneText = if (scenes.isEmpty()) {
                    "（场景未加载）"
                } else {
                    scenes.joinToString("；") { r ->
                        val name = NativeBridge.sceneLabels.getOrElse(r.index) { "class${r.index}" }
                        "$name ${String.format(Locale.US, "%.1f%%", r.prob * 100)}"
                    }
                }
                // 把每帧识别到的类别打出来，方便确认「鼠标/手机」到底有没有被认出来
                val boxText = boxes.joinToString(", ") { b ->
                    val name = NativeBridge.cocoLabels.getOrElse(b.label) { "class${b.label}" }
                    "$name(${String.format(Locale.US, "%.2f", b.score)})"
                }
                // 相机模式不跑 OCR（实时场景用不上，且又慢又闪）
                Log.d(TAG, "frame boxes=${boxes.size} scenes=${scenes.size} detected=[$boxText]")
                runOnUiThread {
                    overlay.setBoxes(boxes, bw, bh)
                    tvInfo.text = "场景: $sceneText"
                    busy = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "推理出错", e)
                runOnUiThread {
                    tvInfo.text = "⚠️ 推理出错：${e.message}"
                    busy = false
                }
            } finally {
                bitmap.recycle() // 预览由 PreviewView 负责，这张分析帧用完即回收
            }
        }
    }

    // 按角度旋转位图（竖屏手机上把横着的相机帧转正）
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // 统一转 ARGB_8888（yoloDetect 要求），避免颜色读错
    private fun toArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        inferenceExecutor.shutdown()
    }
}
