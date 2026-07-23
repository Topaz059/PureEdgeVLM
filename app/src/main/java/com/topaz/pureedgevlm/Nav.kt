package com.topaz.pureedgevlm

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout

// 四页底部导航栏：对话 / 识别 / 相机 / Benchmark
// current 传当前页代号（"dialog" / "image" / "camera" / "bench"），对应按钮置灰并高亮成蓝色
fun buildBottomBar(ctx: Context, current: String): LinearLayout {
    val density = ctx.resources.displayMetrics.density
    val barHeight = (56 * density).toInt()

    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xFFEDEDED.toInt())
    }
    val pages = listOf(
        "dialog" to "对话",
        "image"  to "识别",
        "camera" to "相机",
        "bench"  to "Benchmark"
    )
    for ((key, label) in pages) {
        val btn = Button(ctx).apply {
            text = label
            textSize = 12f      // 英文缩小一号，避免折行
            maxLines = 1        // 强制单行
            setAllCaps(false)
            if (key == current) {
                isEnabled = false
                setTextColor(0xFF3F7DFF.toInt())
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
        bar.addView(btn, LinearLayout.LayoutParams(0, barHeight, 1f))
    }
    return bar
}
