package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnPick: Button
    private lateinit var tvStatus: TextView

    // 选图：打开系统相册，回来拿到图片 Uri
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
        bmp ?: return@registerForActivityResult
        runPipeline(bmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.init(this)

        btnPick = Button(this).apply { text = "选择图片" }
        tvStatus = TextView(this).apply { text = "点按钮选一张图，看检测框 + 场景 Top5 + OCR 文字" }
        imageView = ImageView(this)

        btnPick.setOnClickListener { pickImage.launch("image/*") }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(btnPick)
            addView(tvStatus)
            addView(imageView)
        }
        setContentView(layout)
    }

    // 在后台线程依次跑 YOLO + 场景识别 + OCR，免得界面卡住
    private fun runPipeline(bmp: Bitmap) {
        tvStatus.text = "推理中..."
        Thread {
            // OCR 走 native 时要读像素，统一转成 ARGB_8888，避免格式不符导致读错颜色
            val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                       else bmp.copy(Bitmap.Config.ARGB_8888, false)

            val boxes = NativeBridge.yoloDetect(argb, 0.2f, 0.45f)
            val drawn = drawBoxes(argb, boxes)
            val scenes = NativeBridge.sceneRecognize(argb)
            val sceneText = if (scenes.isEmpty()) {
                "（场景模型未加载）"
            } else {
                scenes.joinToString("\n") { r ->
                    val name = NativeBridge.sceneLabels.getOrElse(r.index) { "class${r.index}" }
                    "· $name  ${String.format(Locale.US, "%.1f%%", r.prob * 100)}"
                }
            }
            val ocrText = NativeBridge.ocrRecognize(argb)
            runOnUiThread {
                imageView.setImageBitmap(drawn)
                tvStatus.text = "检测到 ${boxes.size} 个物体\n场景 Top5:\n$sceneText\n\nOCR 文字:\n$ocrText"
            }
        }.start()
    }

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
