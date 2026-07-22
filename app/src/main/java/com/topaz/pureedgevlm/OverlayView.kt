package com.topaz.pureedgevlm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.min

// 覆盖在相机预览之上的「画框层」：背景透明，只画 YOLO 检测框 + 类别名。
// 坐标映射：相机帧是 imgW×imgH，预览用 FIT_CENTER（等比缩放、居中），
// 所以把框按「视图/图像」的较小缩放比缩放，再居中偏移，就能和画面里的物体对齐。
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var boxes: Array<YoloBox> = emptyArray()
    private var imgW = 1
    private var imgH = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 36f
    }

    // 由相机页在每次推理完成后调用，传入检测框和这一帧的图像尺寸
    fun setBoxes(b: Array<YoloBox>, imageW: Int, imageH: Int) {
        boxes = b
        imgW = imageW
        imgH = imageH
        invalidate() // 触发 onDraw 重绘
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imgW <= 0 || imgH <= 0) return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        // 等比缩放（取较小边，保证不拉伸），居中偏移
        val scale = min(viewW / imgW, viewH / imgH)
        val offX = (viewW - imgW * scale) / 2f
        val offY = (viewH - imgH * scale) / 2f
        for (b in boxes) {
            val left = b.x1 * scale + offX
            val top = b.y1 * scale + offY
            val right = b.x2 * scale + offX
            val bottom = b.y2 * scale + offY
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val name = NativeBridge.cocoLabels.getOrElse(b.label) { "class${b.label}" }
            canvas.drawText(
                "$name ${String.format(Locale.US, "%.2f", b.score)}",
                left,
                top - 6,
                textPaint
            )
        }
    }
}
