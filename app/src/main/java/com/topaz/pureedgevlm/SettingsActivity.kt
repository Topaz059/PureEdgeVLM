package com.topaz.pureedgevlm

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

// 设置页：右上角齿轮入口进入。里面两个滑动开关（持久化到 SharedPreferences）：
//   ① KV 缓存复用（多轮对话）
//   ② 视觉模型并行推理（检测/场景/OCR）
// 风格与四主页面统一：浅冷灰底 + 白底卡片 + 品牌蓝。
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 注意：设置页不加载模型（不调用 NativeBridge.init），纯 UI

        val topBar = appBar(this, "设置", "")

        // —— ① KV 缓存复用 ——
        val kvSwitch = SwitchCompat(this).apply {
            isChecked = Settings.kvReuse(this@SettingsActivity)
            tintBrand()
        }
        val kvRow = settingRow(this, "KV 缓存复用",
            "多轮对话复用常驻上下文，第二轮起首字更快；关掉则每轮整段重算（可用于对比）。", kvSwitch)
        kvSwitch.setOnCheckedChangeListener { _, isOn -> Settings.setKvReuse(this@SettingsActivity, isOn) }

        // —— ② 视觉模型并行推理 ——
        val plSwitch = SwitchCompat(this).apply {
            isChecked = Settings.pipelineParallel(this@SettingsActivity)
            tintBrand()
        }
        val plRow = settingRow(this, "视觉模型并行推理",
            "检测 / 场景 / OCR 三模型并行（关=串行）。阶段五实测并行总耗时更短。", plSwitch)
        plSwitch.setOnCheckedChangeListener { _, isOn -> Settings.setPipelineParallel(this@SettingsActivity, isOn) }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val side = Gui.sideMarginPx(this@SettingsActivity)
            setPadding(side, Gui.dp(this@SettingsActivity, 14f).toInt(),
                side, Gui.dp(this@SettingsActivity, 14f).toInt())
        }
        addVertical(inner, kvRow, this, 10f)
        addVertical(inner, plRow, this, 10f)

        val scroll = ScrollView(this)
        scroll.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Gui.BG)
            addView(topBar)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar(this, "settings"))  // 底部导航：无高亮项，点任意标签返回对应页
        setContentView(root)
    }

    // 一行设置项：标题（左）+ 描述 + 右侧开关，整体做成白底卡片
    private fun settingRow(ctx: SettingsActivity, title: String, desc: String, switch: SwitchCompat): LinearLayout {
        val root = card(ctx)
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val t = TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(Gui.TEXT)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(t)
        top.addView(switch)
        val d = TextView(ctx).apply {
            text = desc
            textSize = 12f
            setTextColor(Gui.TEXT2)
            setPadding(0, Gui.dp(ctx, 6f).toInt(), 0, 0)
        }
        root.addView(top)
        root.addView(d)
        return root
    }

    // 把 SwitchCompat 染成品牌蓝：开=品牌蓝，关=浅灰描边色
    private fun SwitchCompat.tintBrand() {
        val on = Gui.PRIMARY
        val off = Gui.BORDER
        val sl = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
            intArrayOf(on, off)
        )
        trackTintList = sl
        thumbTintList = sl
    }
}
