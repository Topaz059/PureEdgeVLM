package com.topaz.pureedgevlm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

// 四页底部导航栏：对话 / 识别 / 相机 / Benchmark
// 设计：纯文字（按需求保留文字、不做图标），选中项用「蓝色药丸底」高亮，其余灰字；
//      顶部分割线 + 轻阴影，整体更精致。current 传当前页代号。
fun buildBottomBar(ctx: Context, current: String): LinearLayout {
    val density = ctx.resources.displayMetrics.density
    val barHeight = (64 * density).toInt()

    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Gui.SURFACE)
        // 顶部分割线：用 1px 细线视图，避免依赖阴影
    }
    val line = android.view.View(ctx).apply {
        setBackgroundColor(Gui.BORDER)
    }
    // 用一个外层垂直布局把「细线 + 按钮行」包起来
    val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

    val pages = listOf(
        "dialog" to "对话",
        "image"  to "识别",
        "camera" to "相机",
        "bench"  to "Benchmark"
    )
    for ((key, label) in pages) {
        val isCurrent = (key == current)
        val tv = TextView(ctx).apply {
            text = label
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            if (isCurrent) {
                setTextColor(Gui.PRIMARY)
                paint.isFakeBoldText = true
                background = pillBg(ctx, Gui.PILL)   // 蓝色药丸底包住当前项
                val p = (6 * density).toInt()
                setPadding((10 * density).toInt(), p, (10 * density).toInt(), p)
            } else {
                setTextColor(Gui.TEXT2)
                setPadding((10 * density).toInt(), (6 * density).toInt(),
                    (10 * density).toInt(), (6 * density).toInt())
            }
            setOnClickListener {
                val target = when (key) {
                    "dialog" -> MainActivity::class.java
                    "image"  -> ImageActivity::class.java
                    "camera" -> CameraActivity::class.java
                    else      -> BenchmarkActivity::class.java
                }
                val intent = Intent(ctx, target)
                // 若目标页已存在就把它提到最前（保留原状态，比如对话历史），而不是叠新实例
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ctx.startActivity(intent)
            }
        }
        val itemLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER
            topMargin = (10 * density).toInt()
            bottomMargin = (10 * density).toInt()
        }
        bar.addView(tv, itemLp)
    }
    wrap.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, barHeight))
    return wrap
}
