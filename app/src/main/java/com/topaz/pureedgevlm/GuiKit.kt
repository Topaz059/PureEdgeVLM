package com.topaz.pureedgevlm

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// 现代简约浅色 · 统一设计语言（对话 / 识别 / 相机 / Benchmark 四页共用）
// 配色：浅冷灰底 + 纯白卡片 + 品牌蓝点缀
object Gui {
    val BG = 0xFFF4F6F9.toInt()          // 页面背景（浅冷灰）
    val SURFACE = Color.WHITE            // 卡片/表面
    val PRIMARY = 0xFF3F7DFF.toInt()     // 品牌蓝
    val PRIMARY_DARK = 0xFF2E5FD0.toInt()// 品牌蓝（深，按下用）
    val TEXT = 0xFF1F2430.toInt()        // 主文字
    val TEXT2 = 0xFF6B7280.toInt()       // 次要文字
    val BORDER = 0xFFE6E9EF.toInt()      // 描边/分割线
    val PILL = 0xFFE8F0FF.toInt()        // 选中药丸底 / 标签底（浅蓝）

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density)

    // 5% 屏幕宽度的左右外边距（px），让按钮/卡片不贴边
    fun sideMarginPx(ctx: Context) = (0.05f * ctx.resources.displayMetrics.widthPixels).toInt()
}

// 在竖向 LinearLayout 里加一个子项，并可在底部留出 bottomDp 的缝隙（让模块之间不紧贴）
fun addVertical(parent: LinearLayout, child: View, ctx: Context, bottomDp: Float = 0f) {
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    if (bottomDp > 0f) lp.bottomMargin = Gui.dp(ctx, bottomDp).toInt()
    parent.addView(child, lp)
}

// 生成圆角背景（可选描边）。radiusDp 圆角半径，fill 填充色，stroke 描边色（null=不描边）
fun roundBg(ctx: Context, radiusDp: Float, fill: Int, stroke: Int? = null): GradientDrawable {
    return GradientDrawable().apply {
        setColor(fill)
        cornerRadius = Gui.dp(ctx, radiusDp)
        if (stroke != null) setStroke(Math.max(1, Gui.dp(ctx, 1f).toInt()), stroke)
    }
}

// 胶囊背景（两端半圆，用于标签/选中态）
fun pillBg(ctx: Context, fill: Int): GradientDrawable {
    val r = Gui.dp(ctx, 20f)
    return GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(r, r, r, r, r, r, r, r)
    }
}

// 白底卡片：圆角 16 + 轻描边 + 轻阴影，内边距 14
fun card(ctx: Context): LinearLayout {
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(ctx, 16f, Gui.SURFACE, Gui.BORDER)
        setPadding(Gui.dp(ctx, 14f).toInt(), Gui.dp(ctx, 14f).toInt(),
            Gui.dp(ctx, 14f).toInt(), Gui.dp(ctx, 14f).toInt())
        elevation = Gui.dp(ctx, 4f)
    }
}

// 带小标签的分区卡片：返回 (卡片容器, 内容文本框)。内容文本框初始为空，外部调用 .text = ... 更新
fun sectionCard(ctx: Context, label: String): Pair<LinearLayout, TextView> {
    val root = card(ctx)
    val lab = TextView(ctx).apply {
        text = label
        textSize = 11f
        setTextColor(Gui.PRIMARY)
        background = pillBg(ctx, Gui.PILL)
        val p = Gui.dp(ctx, 4f).toInt()
        setPadding(Gui.dp(ctx, 9f).toInt(), p, Gui.dp(ctx, 9f).toInt(), p)
    }
    val valTv = TextView(ctx).apply {
        textSize = 13f
        setTextColor(Gui.TEXT)
        val p = Gui.dp(ctx, 8f).toInt()
        setPadding(0, p, 0, 0)
    }
    root.addView(lab)
    root.addView(valTv)
    return root to valTv
}

// 主按钮：品牌蓝实心、圆角 12、白字
fun primaryButton(ctx: Context, text: String): Button {
    return Button(ctx).apply {
        this.text = text
        setAllCaps(false)
        textSize = 15f
        setTextColor(Color.WHITE)
        background = roundBg(ctx, 12f, Gui.PRIMARY, null)
        val p = Gui.dp(ctx, 13f).toInt()
        setPadding(0, p, 0, p)
    }
}

// 次要按钮：白底、灰字、蓝灰描边
fun ghostButton(ctx: Context, text: String): Button {
    return Button(ctx).apply {
        this.text = text
        setAllCaps(false)
        textSize = 14f
        setTextColor(Gui.TEXT2)
        background = roundBg(ctx, 12f, Gui.SURFACE, Gui.BORDER)
        val p = Gui.dp(ctx, 11f).toInt()
        setPadding(0, p, 0, p)
    }
}

// 顶部标题栏：白底、标题加粗（副标题传空则不显示），底部细分割线
fun appBar(ctx: Context, title: String, subtitle: String): LinearLayout {
    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Gui.SURFACE)
        setPadding(Gui.dp(ctx, 18f).toInt(), Gui.dp(ctx, 18f).toInt(),
            Gui.dp(ctx, 18f).toInt(), Gui.dp(ctx, 14f).toInt())
    }
    val t = TextView(ctx).apply {
        this.text = title
        textSize = 18f
        setTextColor(Gui.TEXT)
        paint.isFakeBoldText = true
    }
    bar.addView(t)
    if (subtitle.isNotBlank()) {
        val s = TextView(ctx).apply {
            this.text = subtitle
            textSize = 12f
            setTextColor(Gui.TEXT2)
            setPadding(0, Gui.dp(ctx, 3f).toInt(), 0, 0)
        }
        bar.addView(s)
    }
    // 底部细线（用一个 1px 高的视图模拟，避免依赖 elevation 阴影）
    val line = android.view.View(ctx).apply {
        setBackgroundColor(Gui.BORDER)
    }
    bar.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
        topMargin = Gui.dp(ctx, 12f).toInt()
    })
    return bar
}

// 带设置入口的标题栏：标题靠左，右上角一个齿轮（⚙），点一下进设置页。
// 与 appBar 同高同风格（白底 + 底部细线），仅多了一个右侧齿轮。
fun appBarSettings(ctx: Context, title: String): LinearLayout {
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Gui.SURFACE)
        setPadding(Gui.dp(ctx, 18f).toInt(), Gui.dp(ctx, 16f).toInt(),
            Gui.dp(ctx, 14f).toInt(), Gui.dp(ctx, 8f).toInt())
    }
    val t = TextView(ctx).apply {
        this.text = title
        textSize = 18f
        setTextColor(Gui.TEXT)
        paint.isFakeBoldText = true
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val gear = TextView(ctx).apply {
        text = "⚙"                       // 齿轮符号
        textSize = 22f
        setTextColor(Gui.TEXT2)
        gravity = Gravity.CENTER
        setPadding(Gui.dp(ctx, 8f).toInt(), Gui.dp(ctx, 4f).toInt(),
            Gui.dp(ctx, 8f).toInt(), Gui.dp(ctx, 4f).toInt())
        // 点齿轮进设置页（仅从 Activity 调用，ctx 必为 Activity，startActivity 安全）
        setOnClickListener { ctx.startActivity(android.content.Intent(ctx, SettingsActivity::class.java)) }
    }
    row.addView(t)
    row.addView(gear)

    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Gui.SURFACE)
    }
    bar.addView(row)
    val line = android.view.View(ctx).apply { setBackgroundColor(Gui.BORDER) }
    bar.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
        topMargin = Gui.dp(ctx, 8f).toInt()
    })
    return bar
}
