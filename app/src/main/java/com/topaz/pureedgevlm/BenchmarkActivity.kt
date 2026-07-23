package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

// 阶段五：Benchmark 独立成「页」。
// 做法：把原本 MainActivity 里的「跑 Benchmark」按钮 + 测速逻辑搬到这里，
// 主界面、相机页、本页三个页通过底部导航栏（Nav.kt 的 buildBottomBar）互相切换。
// 视觉三模型由 MainActivity 启动时已加载（同进程常驻），本页直接复用；
// 大模型按需加载（ensureLlmLoaded），没加载过就在这里第一次加载。
@SuppressLint("SetTextI18n")
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var btnRun: Button
    private lateinit var tvInfo: TextView
    private lateinit var tvResult: TextView

    // 防止连点导致并发跑测速
    @Volatile private var isBusy = false
    // Activity 销毁后不再更新 UI，避免崩溃
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tvInfo = TextView(this).apply {
            text = "点「跑 Benchmark」：对 YOLO/场景/OCR/大模型 四个模型按线程 1/2/4/8 各跑若干次测速，并在 224/640/960 三种分辨率下重复，结果存成 CSV。"
        }
        btnRun = Button(this).apply { text = "跑 Benchmark（测速，写 CSV）" }
        tvResult = TextView(this).apply { text = "" }

        btnRun.setOnClickListener { if (!isBusy) runBenchmark() }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(tvInfo)
            addView(btnRun)
            addView(tvResult)
        }

        // 三页底部导航（当前页 = bench）
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar(this, "bench"))
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

    private fun setBusy(b: Boolean) {
        isBusy = b
        safeUi { btnRun.isEnabled = !b }
    }

    // 在后台线程跑测速，结果 CSV 写到外部存储，摘要回显到界面。
    // 分辨率维度：在 224/640/960 三种输入边长下各跑一遍 benchmarkRun；
    // 每次调用前先删旧 CSV，native 以追加模式写（首次写表头），三种分辨率拼成一个完整 CSV。
    private fun runBenchmark() {
        setBusy(true)
        tvInfo.text = "Benchmark 中（大模型较慢，请稍候，别切走）..."
        Thread {
            var bmp: Bitmap? = null
            try {
                // 大模型若已就绪就一起测，否则只测视觉三模型（C++ 里会跳过未加载的 LLM）
                NativeBridge.ensureLlmLoaded(this@BenchmarkActivity)
                val dir = getExternalFilesDir(null) ?: filesDir
                val csvFile = java.io.File(dir, "benchmark.csv")
                if (csvFile.exists()) csvFile.delete()  // 先删旧文件，保证从干净 CSV 开始
                val csv = csvFile.absolutePath
                val resolutions = intArrayOf(224, 640, 960)
                val sb = StringBuilder()
                for (res in resolutions) {
                    bmp?.recycle()
                    bmp = makeTestBitmap(res)
                    val summary = NativeBridge.benchmarkRun(bmp, csv, 10, res)
                    sb.append("分辨率 ").append(res).append("×").append(res).append("：\n")
                       .append(summary).append("\n\n")
                }
                safeUi {
                    tvResult.text = "结果已保存到：\n$csv\n\n${sb.toString()}可用电脑执行：python models_workspace/benchmark_to_markdown.py \"$csv\""
                    tvInfo.text = "Benchmark 完成（3 种分辨率已测）。"
                    setBusy(false)
                }
            } catch (e: Exception) {
                Log.e("BenchmarkActivity", "Benchmark 出错", e)
                safeUi {
                    tvInfo.text = "⚠️ Benchmark 出错：${e.message}（详见 Logcat tag=BenchmarkActivity）"
                    setBusy(false)
                }
            } finally {
                bmp?.recycle()
            }
        }.start()
    }

    // 生成一张带图形和文字的测试图（供 Benchmark 计时用，不要求识别正确）。
    // size = 位图边长（像素），图形按 size/640 比例缩放，保证不同分辨率下构图一致。
    private fun makeTestBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(210, 210, 210))
        val p = Paint().apply { style = Paint.Style.FILL }
        val s = size / 640f
        p.color = Color.RED;   c.drawCircle(180f * s, 200f * s, 80f * s, p)
        p.color = Color.BLUE;   c.drawRect(300f * s, 250f * s, 460f * s, 420f * s, p)
        p.color = Color.BLACK;  p.textSize = 40f * s
        c.drawText("PureEdgeVLM Benchmark", 40f * s, 90f * s, p)
        return bmp
    }
}
