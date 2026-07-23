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
// 视觉三模型由启动时 NativeBridge.init() 加载（同进程常驻），本页直接复用。
// 界面：顶部标题栏 + 图片卡片 + 蓝色「选择图片」按钮 + 检测/场景/OCR 三块分区卡片。
@SuppressLint("SetTextI18n")
class ImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnPick: Button
    private lateinit var tvStatus: TextView
    private lateinit var detTv: TextView
    private lateinit var sceneTv: TextView
    private lateinit var ocrTv: TextView

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

        val topBar = appBar(this, "视觉识别", "")

        tvStatus = TextView(this).apply {
            textSize = 13f
            setTextColor(Gui.TEXT2)
            val p = Gui.dp(this@ImageActivity, 4f).toInt()
            setPadding(0, p, 0, p)
        }

        // 图片卡片：圆角裁切的 ImageView
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundBg(this@ImageActivity, 12f, 0xFFEEF1F7.toInt())
            clipToOutline = true
            minimumHeight = Gui.dp(this@ImageActivity, 180f).toInt()
        }
        val imageCard = card(this).apply {
            addView(imageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        btnPick = primaryButton(this, "选择图片（检测 / 场景 / OCR）")
        btnPick.setOnClickListener { if (!isBusy) pickImage.launch("image/*") }

        val (detCard, det) = sectionCard(this, "物体检测")
        val (sceneCard, scene) = sectionCard(this, "场景 Top5")
        val (ocrCard, ocr) = sectionCard(this, "OCR 文字")
        detTv = det; sceneTv = scene; ocrTv = ocr
        detTv.text = "（未识别）"
        sceneTv.text = "（未识别）"
        ocrTv.text = "（未识别）"

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = Gui.sideMarginPx(this@ImageActivity)
            setPadding(side, Gui.dp(this@ImageActivity, 14f).toInt(), side, Gui.dp(this@ImageActivity, 14f).toInt())
        }
        // 每个模块之间留 6dp 缝隙，不紧贴
        addVertical(inner, tvStatus, this@ImageActivity, 6f)
        addVertical(inner, imageCard, this@ImageActivity, 6f)
        addVertical(inner, btnPick, this@ImageActivity, 6f)
        addVertical(inner, detCard, this@ImageActivity, 6f)
        addVertical(inner, sceneCard, this@ImageActivity, 6f)
        addVertical(inner, ocrCard, this@ImageActivity, 6f)

        // 内容放进 ScrollView，保证底部 OCR 卡片不被截掉、可下拉查看
        val scroll = ScrollView(this)
        scroll.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Gui.BG)
            addView(topBar)   // 顶部标题栏满宽，与对话页对齐
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        // 四页底部导航（当前页 = 识别）
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

    // 阶段二/三：YOLO + 场景 + OCR，结果分别写进三块分区卡片
    private fun runPipeline(bmp: Bitmap) {
        setBusy(true)
        tvStatus.text = "推理中…"
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
                val detText = if (boxes.isEmpty()) "（未检测到物体）" else
                    boxes.joinToString(" · ") { b ->
                        val name = NativeBridge.cocoLabels.getOrElse(b.label) { "class${b.label}" }
                        "$name ${String.format(Locale.US, "%.2f", b.score)}"
                    }
                safeUi {
                    imageView.setImageBitmap(drawn)
                    detTv.text = detText
                    sceneTv.text = sceneText
                    ocrTv.text = if (ocrText.isBlank()) "未识别到文字" else ocrText
                    tvStatus.text = "识别完成（检测到 ${boxes.size} 个物体）"
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
