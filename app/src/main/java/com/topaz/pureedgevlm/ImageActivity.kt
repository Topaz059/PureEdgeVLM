package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

// 阶段五：把「选择图片」视觉推理也独立成「页」。
// 做法：原本混在对话页顶部的「选择图片」按钮 + YOLO/场景/OCR 推理逻辑搬到这里，
// 和对话 / 相机 / Benchmark 一样通过底部导航栏（Nav.kt 的 buildBottomBar）互相切换。
// 视觉三模型由启动时 NativeBridge.init() 加载（同进程常驻），本页直接复用。
@SuppressLint("SetTextI18n")
class ImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnPick: Button
    private lateinit var tvStatus: TextView

    // 防止连点导致并发推理
    @Volatile private var isBusy = false
    // Activity 销毁后不再更新 UI，避免崩溃
    private var destroyed = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
            if (!isBitmapValid(bmp)) {
                Toast.makeText(this, "图片无法解码，换一张试试", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            runPipeline(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "读取图片失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.init(this)

        btnPick = Button(this).apply { text = "选择图片（检测/场景/OCR）" }
        imageView = ImageView(this)
        tvStatus = TextView(this).apply {
            text = "点「选择图片」跑视觉三模型：YOLO 物体检测 + 场景识别 + 文字识别（OCR），结果画在图上并列出。"
        }

        btnPick.setOnClickListener { if (!isBusy) pickImage.launch("image/*") }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(btnPick)
            addView(imageView)
            addView(tvStatus)
        }

        // 四页底部导航（当前页 = 识别）；上面 content 占满剩余空间，导航栏固定底部
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar(this, "image"))
        setContentView(root)
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    // UI 更新封装：Activity 销毁后跳过，避免更新已销毁的界面导致崩溃
    private fun safeUi(block: () -> Unit) {
        if (destroyed) return
        runOnUiThread(block)
    }

    private fun isBitmapValid(bmp: Bitmap?): Boolean =
        bmp != null && bmp.width > 0 && bmp.height > 0

    private fun setBusy(b: Boolean) {
        isBusy = b
        safeUi { btnPick.isEnabled = !b }
    }

    // 阶段二/三：YOLO + 场景 + OCR，结果画到图上并显示
    private fun runPipeline(bmp: Bitmap) {
        setBusy(true)
        tvStatus.text = "推理中..."
        Thread {
            var argb: Bitmap? = null
            try {
                argb = toArgb(bmp)
                val boxes = NativeBridge.yoloDetect(argb, 0.2f, 0.45f)
                val drawn = drawBoxes(argb, boxes)
                val scenes = NativeBridge.sceneRecognize(argb)
                val sceneText = if (scenes.isEmpty()) {
                    "（场景模型未加载）"
                } else {
                    scenes.joinToString("；") { r ->
                        val name = NativeBridge.sceneLabels.getOrElse(r.index) { "class${r.index}" }
                        "$name ${String.format(Locale.US, "%.1f%%", r.prob * 100)}"
                    }
                }
                val ocrText = NativeBridge.ocrRecognize(argb)
                safeUi {
                    imageView.setImageBitmap(drawn)
                    tvStatus.text = "检测到 ${boxes.size} 个物体\n场景 Top5:\n$sceneText\n\nOCR 文字:\n$ocrText"
                    setBusy(false)
                }
            } catch (e: Exception) {
                Log.e("ImageActivity", "视觉 pipeline 出错", e)
                safeUi {
                    tvStatus.text = "⚠️ 推理出错：${e.message}（详见 Logcat tag=ImageActivity）"
                    setBusy(false)
                }
            } finally {
                if (argb != null && argb != bmp) argb.recycle()
            }
        }.start()
    }

    // 统一转成 ARGB_8888，避免格式不符导致读错颜色
    private fun toArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)

    // 把框和类别名画到图上
    private fun drawBoxes(bmp: Bitmap, boxes: Array<YoloBox>): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 36f
        }
        for (b in boxes) {
            canvas.drawRect(b.x1, b.y1, b.x2, b.y2, boxPaint)
            val name = NativeBridge.cocoLabels.getOrElse(b.label) { "class${b.label}" }
            canvas.drawText("$name ${String.format(Locale.US, "%.2f", b.score)}", b.x1, b.y1 - 6, textPaint)
        }
        return out
    }
}
