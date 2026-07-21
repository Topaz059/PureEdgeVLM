package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var btnPick: Button
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView

    // 防止「连点」导致并发调用大模型（阶段四稳定性要求）
    @Volatile private var isBusy = false

    // 多轮对话历史：每条是 (是否用户发言, 文本)
    private val history = mutableListOf<Pair<Boolean, String>>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)) ?: return@registerForActivityResult
        runPipeline(bmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeBridge.init(this)

        btnPick = Button(this).apply { text = "选择图片（检测/场景/OCR）" }
        imageView = ImageView(this)
        tvStatus = TextView(this).apply {
            text = "上方点「选择图片」跑视觉三模型；下方是与本地大模型（MiniCPM5）的纯文字多轮对话。"
        }

        val divider = TextView(this).apply { text = "———— 本地对话（MiniCPM5，纯文字多轮）————" }

        chatScroll = ScrollView(this)
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        chatScroll.addView(chatContainer)

        etInput = EditText(this).apply {
            hint = "输入想说的话…"
            maxLines = 4
        }
        btnSend = Button(this).apply { text = "发送" }
        btnClear = Button(this).apply { text = "清空" }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(etInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnSend)
            addView(btnClear)
        }

        btnPick.setOnClickListener { if (!isBusy) pickImage.launch("image/*") }
        btnSend.setOnClickListener { if (!isBusy) sendMessage() }
        btnClear.setOnClickListener { if (!isBusy) clearChat() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(btnPick)
            addView(imageView)
            addView(tvStatus)
            addView(divider)
            // 对话区占满剩余空间，输入框固定底部
            addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow)
        }
        setContentView(layout)
    }

    // 阶段二/三：YOLO + 场景 + OCR，结果画到图上并显示（与对话功能互不干扰）
    private fun runPipeline(bmp: Bitmap) {
        setBusy(true)
        tvStatus.text = "推理中..."
        Thread {
            val argb = toArgb(bmp)
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
            if (argb != bmp) argb.recycle()
            runOnUiThread {
                imageView.setImageBitmap(drawn)
                tvStatus.text = "检测到 ${boxes.size} 个物体\n场景 Top5:\n$sceneText\n\nOCR 文字:\n$ocrText"
                setBusy(false)
            }
        }.start()
    }

    // 阶段四：纯文字多轮对话
    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.text.clear()

        // 1) 把用户这句话加入历史并立刻显示
        addMessage(text, true)
        history.add(true to text)
        // 控制历史长度，避免超出上下文窗口（n_ctx=2048）
        while (history.size > 16) history.removeAt(0)

        // 2) 准备一个 AI 气泡，生成时逐字更新
        val aiBubble = makeBubble(false)
        chatContainer.addView(aiBubble)
        scrollChatToBottom()

        setBusy(true)
        Thread {
            // 3) 用 MiniCPM5 的 ChatML 模板拼成「完整多轮对话」再喂给模型
            val prompt = buildChatPrompt(history)
            if (!NativeBridge.ensureLlmLoaded(this@MainActivity)) {
                runOnUiThread {
                    aiBubble.text = "（大模型加载失败，请看 Logcat tag=LLM）"
                    setBusy(false)
                }
                return@Thread
            }

            // 4) 生成，逐字回调（打字机效果）
            val sb = StringBuilder()
            NativeBridge.llmGenerate(prompt, 256, object : LlmCallback {
                override fun onToken(piece: String) {
                    sb.append(piece)
                    runOnUiThread {
                        aiBubble.text = sb.toString()
                        scrollChatToBottom()
                    }
                }
            })

            val reply = sb.toString()
            runOnUiThread {
                if (reply.isBlank()) aiBubble.text = "（模型未输出，请看 Logcat tag=LLM）"
                setBusy(false)
            }
            // 5) 把模型回复加入历史，下一轮对话才能"记得上文"
            if (reply.isNotBlank()) history.add(false to reply)
        }.start()
    }

    // 用 MiniCPM5 的 ChatML 模板拼装完整多轮对话：
    // system 系统设定 + 每一轮 user/assistant + 末尾的 assistant 前缀（不含 <|im_end|>）。
    private fun buildChatPrompt(hist: List<Pair<Boolean, String>>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n你是一个运行在手机上的本地智能助手，请用简洁、自然的中文回答。<|im_end|>\n")
        for ((isUser, t) in hist) {
            val role = if (isUser) "user" else "assistant"
            sb.append("<|im_start|>$role\n$t<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // 生成一条对话气泡（用户=蓝底白字靠右，AI=灰底深字靠左），但不加入容器
    private fun makeBubble(isUser: Boolean): TextView {
        val tv = TextView(this)
        val pad = (8 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        tv.setTextColor(if (isUser) Color.WHITE else Color.DKGRAY)
        val bg = if (isUser) Color.parseColor("#3F7DFF") else Color.parseColor("#ECECEC")
        val gd = GradientDrawable()
        gd.setColor(bg)
        gd.cornerRadius = (12 * resources.displayMetrics.density)
        tv.background = gd
        tv.gravity = if (isUser) Gravity.END else Gravity.START
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 6, 0, 6)
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
        return tv
    }

    // 立刻把一条消息显示到对话区
    private fun addMessage(text: String, isUser: Boolean) {
        val tv = makeBubble(isUser)
        tv.text = text
        chatContainer.addView(tv)
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // 清空对话：历史与界面一起清掉（大模型不卸载，下次直接复用）
    private fun clearChat() {
        history.clear()
        chatContainer.removeAllViews()
    }

    // 统一转成 ARGB_8888，避免格式不符导致读错颜色
    private fun toArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)

    private fun setBusy(b: Boolean) {
        isBusy = b
        runOnUiThread {
            btnPick.isEnabled = !b
            btnSend.isEnabled = !b
            btnClear.isEnabled = !b
        }
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
