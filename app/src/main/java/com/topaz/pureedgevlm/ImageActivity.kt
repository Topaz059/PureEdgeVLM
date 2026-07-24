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
import java.io.File
import java.util.Locale

// 阶段五：把「选择图片」视觉推理也独立成「页」。
// 视觉三模型由启动时 NativeBridge.init() 加载（同进程常驻），本页直接复用。
// 界面：顶部标题栏 + 图片卡片 + 蓝色「选择图片」按钮 + 检测/场景/OCR 三块分区卡片。
// 另含「串行/并行」模式开关与「批量测速(10次)」按钮：批量测速用固定 640 测试图跑 N 次，
// 把每次 总耗时+分模型耗时 写 CSV（按模式命名），配合 models_workspace/recognition_bench_to_markdown.py 出 Markdown。
@SuppressLint("SetTextI18n")
class ImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnPick: Button
    private lateinit var tvStatus: TextView
    private lateinit var detTv: TextView
    private lateinit var sceneTv: TextView
    private lateinit var ocrTv: TextView
    private lateinit var btnMode: Button
    private lateinit var btnBatch: Button
    // 流水线模式：true=三模型并行，false=串行。单张识别与批量测速都按它走。
    // 走 Settings（设置页开关持久化）；页内 btnMode 也读写同一份，二者自动同步。
    private var pipelineParallel: Boolean
        get() = Settings.pipelineParallel(this@ImageActivity)
        set(v) = Settings.setPipelineParallel(this@ImageActivity, v)

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

        val topBar = appBarSettings(this, "视觉识别")

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

        // 串行/并行 模式开关（单张识别与批量测速都按它走）；与设置页开关共用 Settings，自动同步
        btnMode = Button(this).apply {
            text = if (pipelineParallel) "模式：并行" else "模式：串行"
            setOnClickListener {
                pipelineParallel = !pipelineParallel
                text = if (pipelineParallel) "模式：并行" else "模式：串行"
            }
        }
        // 批量测速：用固定测试图跑 10 次，写 CSV（按模式命名，前/后分开存）
        btnBatch = primaryButton(this, "批量测速（10 次，写 CSV）")
        btnBatch.setOnClickListener { if (!isBusy) runBatch(10) }

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
        addVertical(inner, btnMode, this@ImageActivity, 6f)
        addVertical(inner, btnBatch, this@ImageActivity, 6f)
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
        safeUi {
            btnPick.isEnabled = !b
            btnMode.isEnabled = !b
            btnBatch.isEnabled = !b
        }
    }

    // 单次推理结果 + 各阶段耗时（毫秒）
    private data class PipeResult(
        val totalMs: Long, val yoloMs: Long, val sceneMs: Long, val ocrMs: Long,
        val boxes: Array<YoloBox>, val scenes: Array<SceneResult>, val ocr: String
    )

    // 核心：跑一次视觉流水线。parallel=true 三模型并行，否则串行。
    // 注意：只计「模型推理」耗时，不含图片格式转换(toArgb)与画框(drawBoxes)。
    private fun runInference(ab: Bitmap, parallel: Boolean): PipeResult {
        val t0 = System.currentTimeMillis()
        var boxes: Array<YoloBox>? = null
        var scenes: Array<SceneResult>? = null
        var ocrText: String? = null
        var yoloMs = 0L; var sceneMs = 0L; var ocrMs = 0L

        if (parallel) {
            // 三模型并行：各自独立线程，CountDownLatch 等齐；任一异常存变量、主线程 rethrow
            val latch = java.util.concurrent.CountDownLatch(3)
            var yoloErr: Throwable? = null; var sceneErr: Throwable? = null; var ocrErr: Throwable? = null
            Thread { val s = System.currentTimeMillis(); try { boxes = NativeBridge.yoloDetect(ab, 0.2f, 0.45f) } catch (e: Throwable) { yoloErr = e } finally { yoloMs = System.currentTimeMillis() - s; latch.countDown() } }.start()
            Thread { val s = System.currentTimeMillis(); try { scenes = NativeBridge.sceneRecognize(ab) } catch (e: Throwable) { sceneErr = e } finally { sceneMs = System.currentTimeMillis() - s; latch.countDown() } }.start()
            Thread { val s = System.currentTimeMillis(); try { ocrText = NativeBridge.ocrRecognize(ab) } catch (e: Throwable) { ocrErr = e } finally { ocrMs = System.currentTimeMillis() - s; latch.countDown() } }.start()
            latch.await()
            yoloErr?.let { throw RuntimeException("YOLO 推理失败", it) }
            sceneErr?.let { throw RuntimeException("场景推理失败", it) }
            ocrErr?.let { throw RuntimeException("OCR 推理失败", it) }
        } else {
            // 串行：依次跑，每步单独计时
            var s = System.currentTimeMillis(); boxes = NativeBridge.yoloDetect(ab, 0.2f, 0.45f); yoloMs = System.currentTimeMillis() - s
            s = System.currentTimeMillis(); scenes = NativeBridge.sceneRecognize(ab); sceneMs = System.currentTimeMillis() - s
            s = System.currentTimeMillis(); ocrText = NativeBridge.ocrRecognize(ab); ocrMs = System.currentTimeMillis() - s
        }

        val elapsed = System.currentTimeMillis() - t0
        return PipeResult(elapsed, yoloMs, sceneMs, ocrMs,
            boxes ?: emptyArray(), scenes ?: emptyArray(), ocrText ?: "")
    }

    // 阶段二/三：单张图推理 + 显示（选择图片按钮走这里，用当前模式）
    private fun runPipeline(bmp: Bitmap) {
        setBusy(true)
        tvStatus.text = "推理中…"
        Thread {
            var argb: Bitmap? = null
            try {
                argb = toArgb(bmp)
                val ab = argb!!
                val r = runInference(ab, pipelineParallel)
                val bx = r.boxes
                val drawn = drawBoxes(ab, bx)
                val sceneText = if (r.scenes.isEmpty()) {
                    "（场景模型未加载）"
                } else {
                    r.scenes.joinToString("；") { sc ->
                        val name = NativeBridge.sceneLabels.getOrElse(sc.index) { "class${sc.index}" }
                        "$name ${String.format(Locale.US, "%.1f%%", sc.prob * 100)}"
                    }
                }
                val detText = if (bx.isEmpty()) "（未检测到物体）" else
                    bx.joinToString(" · ") { b ->
                        val name = NativeBridge.cocoLabels.getOrElse(b.label) { "class${b.label}" }
                        "$name ${String.format(Locale.US, "%.2f", b.score)}"
                    }
                safeUi {
                    imageView.setImageBitmap(drawn)
                    detTv.text = detText
                    sceneTv.text = sceneText
                    ocrTv.text = if (r.ocr.isBlank()) "未识别到文字" else r.ocr
                    tvStatus.text = "识别完成（检测到 ${bx.size} 个物体） · 总耗时 ${r.totalMs} ms（YOLO ${r.yoloMs} / 场景 ${r.sceneMs} / OCR ${r.ocrMs}）"
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

    // 批量测速：用固定测试图跑 n 次，把每次「总耗时+分模型耗时」写 CSV（按模式命名，前/后分开存）。
    // CSV 列：run,mode,total_ms,yolo_ms,scene_ms,ocr_ms —— 用电脑执行 recognition_bench_to_markdown.py 转 Markdown。
    // 注意：CSV 必须写到内部存储 filesDir（/data/data/.../files），安卓 11+ 外部 Android/data 受限，
    //       取文件用：adb shell run-as com.topaz.pureedgevlm cat /data/data/com.topaz.pureedgevlm/files/xxx.csv > 本地文件
    private fun runBatch(n: Int) {
        setBusy(true)
        tvStatus.text = "批量测速中（0/$n）…"
        Thread {
            try {
                val ab = makeTestBitmap(640)  // 固定测试图，保证前后两次输入一致、可比
                val mode = if (pipelineParallel) "parallel" else "serial"
                val rows = mutableListOf<LongArray>()
                for (i in 1..n) {
                    val r = runInference(ab, pipelineParallel)
                    rows.add(longArrayOf(r.totalMs, r.yoloMs, r.sceneMs, r.ocrMs))
                    safeUi { tvStatus.text = "批量测速中（$i/$n）…" }
                }
                ab.recycle()

                val dir = filesDir  // 内部存储 /data/data/.../files，便于 adb run-as 提取（Android 11+ 外部 Android/data 受限）
                val csvFile = File(dir, "recognition_bench_$mode.csv")
                csvFile.printWriter().use { w ->
                    w.println("run,mode,total_ms,yolo_ms,scene_ms,ocr_ms")
                    rows.forEachIndexed { i, a -> w.println("${i + 1},$mode,${a[0]},${a[1]},${a[2]},${a[3]}") }
                }

                val avg = { idx: Int -> rows.map { it[idx] }.average() }
                val tAvg = avg(0); val yAvg = avg(1); val sAvg = avg(2); val oAvg = avg(3)
                val serialCsv = File(dir, "recognition_bench_serial.csv").absolutePath
                val parallelCsv = File(dir, "recognition_bench_parallel.csv").absolutePath
                safeUi {
                    tvStatus.text = "批量测速完成（${mode}，共 $n 次）\n" +
                        "平均 总耗时 ${tAvg.toInt()} ms（YOLO ${yAvg.toInt()} / 场景 ${sAvg.toInt()} / OCR ${oAvg.toInt()}）\n" +
                        "CSV：${csvFile.absolutePath}\n\n" +
                        "导出文档（本机执行）：\n" +
                        "python models_workspace/recognition_bench_to_markdown.py \"${csvFile.absolutePath}\"\n" +
                        "前后对比：\n" +
                        "python models_workspace/recognition_bench_to_markdown.py --before \"$serialCsv\" --after \"$parallelCsv\""
                    setBusy(false)
                }
            } catch (e: Exception) {
                Log.e("ImageActivity", "批量测速出错", e)
                safeUi {
                    tvStatus.text = "⚠️ 批量测速出错：${e.message}（详见 Logcat tag=ImageActivity）"
                    setBusy(false)
                }
            }
        }.start()
    }

    // 固定测试图（与 Benchmark 同款构图，保证前后两次测速输入一致、可比；不要求识别正确）
    private fun makeTestBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(210, 210, 210))
        val p = Paint().apply { style = Paint.Style.FILL }
        val s = size / 640f
        p.color = Color.RED;   c.drawCircle(180f * s, 200f * s, 80f * s, p)
        p.color = Color.BLUE;   c.drawRect(300f * s, 250f * s, 460f * s, 420f * s, p)
        p.color = Color.BLACK;  p.textSize = 40f * s
        c.drawText("PureEdgeVLM Bench", 40f * s, 90f * s, p)
        return bmp
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
