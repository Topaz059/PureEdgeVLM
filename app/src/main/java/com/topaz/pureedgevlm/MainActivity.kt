package com.topaz.pureedgevlm

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {

    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView

    // 防止「连点」导致并发调用大模型（阶段四稳定性要求）
    @Volatile private var isBusy = false

    // 错误处理：Activity 销毁后不再更新 UI，避免崩溃
    private var destroyed = false

    // 多轮对话历史：每条是 (是否用户发言, 文本)
    private val history = mutableListOf<Pair<Boolean, String>>()

    // 历史占用的 token 预算上限（按"字符≈token"故意高估；n_ctx=2048，生成上限 1280，留约 700 给提示词含历史）
    // 超出就丢最早的整轮对话，保证 prompt 不会撑爆上下文、prefill 不暴涨
    private val HISTORY_TOKEN_BUDGET = 600

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()  // 启动页：先显示蓝底相机图标，随后淡出到主界面
        NativeBridge.init(this)

        tvStatus = TextView(this).apply {
            text = "与本地大模型（MiniCPM5）的纯文字多轮对话。底部可切到「识别 / 相机 / Benchmark」。"
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

        btnSend.setOnClickListener { if (!isBusy) sendMessage() }
        btnClear.setOnClickListener { if (!isBusy) clearChat() }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(tvStatus)
            addView(divider)
            // 对话区占满剩余空间，输入框固定底部
            addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(inputRow)
        }
        // 四页底部导航（当前页 = 对话）；上面 layout 占满剩余空间，导航栏固定底部
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(layout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar(this, "dialog"))
        setContentView(root)
        checkModelsOnStart()
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

    // 启动时查一次各模型加载状态，缺模型在状态栏给明确提示（不阻断其它功能）
    private fun checkModelsOnStart() {
        try {
            val status = NativeBridge.modelStatus()
            // LLM 是按需加载，启动时未加载属于正常，不在此处提示
            val missing = status.split(";").filter { it.endsWith("missing") }
                .map { it.substringBefore("=") }
                .filter { it != "llm" }
            if (missing.isNotEmpty()) {
                tvStatus.text = "⚠️ 部分模型未加载：${missing.joinToString(", ")}\n其它功能仍可正常使用。"
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "modelStatus 查询失败", e)
        }
    }

    // 阶段四：纯文字多轮对话
    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.text.clear()

        // 1) 把用户这句话加入历史并立刻显示（带淡入上移动画）
        addMessage(text, true)
        history.add(true to text)
        // 控制历史长度：按"估算 token 预算"裁剪，避免多轮累积导致 prefill 暴涨
        while (history.size > 2 && estHistoryTokens() > HISTORY_TOKEN_BUDGET) {
            history.removeAt(0)   // 丢最早的一条（通常是 user）
            history.removeAt(0)   // 连同它对应的 assistant 回复，保持一问一答成对
        }

        // 2) 先放一个"思考中"气泡（三个跳动圆点），告诉用户 AI 在忙
        val thinking = makeThinkingBubble()
        chatContainer.addView(thinking)
        scrollChatToBottom()
        animateIn(thinking)

        setBusy(true)
        Thread {
            try {
                // 3) 用 MiniCPM5 的 ChatML 模板拼成「完整多轮对话」再喂给模型
                val prompt = buildChatPrompt(history)
                if (!NativeBridge.ensureLlmLoaded(this@MainActivity)) {
                    safeUi {
                        replaceThinkingWithText(thinking, "（大模型加载失败，请看 Logcat tag=LLM）")
                        setBusy(false)
                    }
                    return@Thread
                }

                // 4) 生成，逐字回调（打字机效果，直接更新文字，无闪烁光标）
                //    模型会吐 <think>...</think> 思考链：生成途中界面显示"思考过程"让用户能看着它想；
                //    一旦出现 </think>，界面只显示正式回答（去掉开头空行）；存进历史的也只保留正式回答，
                //    下一轮才不会把思考内容又喂回模型（否则历史会再次膨胀）。
                val rawSb = StringBuilder()
                var aiBubble: TextView? = null
                var firstToken = true
                NativeBridge.llmGenerate(prompt, 1280, object : LlmCallback {
                    override fun onToken(bytes: ByteArray) {
                        // C++ 传回的是完整 UTF-8 字节，这里按 UTF-8 解码（emoji/中文都稳）
                        rawSb.append(String(bytes, Charsets.UTF_8))
                        val display = displayFor(rawSb.toString())
                        safeUi {
                            if (firstToken) {
                                // 第一个字来了：把"思考中"气泡换成真正的文字气泡
                                firstToken = false
                                val idx = chatContainer.indexOfChild(thinking)
                                chatContainer.removeView(thinking)
                                aiBubble = makeBubble(false)
                                if (idx >= 0) chatContainer.addView(aiBubble, idx) else chatContainer.addView(aiBubble)
                                animateIn(aiBubble!!)
                            }
                            aiBubble!!.text = display
                            scrollChatToBottom()
                        }
                    }
                })

                // 5) 只把"正式回答"（剥离思考过程）存进历史，下一轮对话才能"记得上文"
                val reply = displayFor(rawSb.toString()).trim()
                safeUi {
                    if (firstToken) {
                        // 一个字都没出（异常/空回复）：把思考气泡换成提示文字
                        replaceThinkingWithText(
                            thinking,
                            if (reply.isBlank()) "（模型未输出，请看 Logcat tag=LLM）" else reply
                        )
                    }
                    setBusy(false)
                }
                if (reply.isNotBlank()) history.add(false to reply)
            } catch (e: Exception) {
                Log.e("MainActivity", "对话生成出错", e)
                safeUi {
                    replaceThinkingWithText(thinking, "⚠️ 对话出错：${e.message}（详见 Logcat tag=LLM）")
                    setBusy(false)
                }
            }
        }.start()
    }

    // 用 MiniCPM5 的 ChatML 模板拼装完整多轮对话：
    // system 系统设定 + 每一轮 user/assistant + 末尾的 assistant 前缀（不含 <|im_end|>）。
    private fun buildChatPrompt(hist: List<Pair<Boolean, String>>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n你是一个运行在手机上的本地智能助手，请用简洁、自然的简体中文回答。<|im_end|>\n")
        for ((isUser, t) in hist) {
            val role = if (isUser) "user" else "assistant"
            sb.append("<|im_start|>$role\n$t<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // 根据当前已生成内容决定界面该显示什么：
    // ① 还没出现 <think> → 直接显示（一般是答案）；
    // ② 正在思考（有 <think> 但还没 </think>）→ 显示"思考过程"标题 + 思考内容，让用户能看着它想；
    // ③ 思考结束（出现 </think>）→ 只显示正式回答，并且去掉回答开头可能带的空行。
    private fun displayFor(raw: String): String {
        val open = raw.indexOf("<think>")
        if (open < 0) return raw
        val close = raw.indexOf("</think>", open)
        if (close < 0) {
            // 正在思考：展示思考内容，加个标题便于区分
            val think = raw.substring(open + "<think>".length)
            return "🤔 思考过程：\n$think"
        }
        // 思考已结束：只保留正式回答，去掉开头的换行/空格（模型常在 </think> 后多打空行）
        val answer = raw.substring(close + "</think>".length)
        return answer.trimStart()
    }

    // 粗略估算历史占用的 token 数（中文为主，1 字符≈1 token，这里故意高估以保证不超窗）
    private fun estHistoryTokens(): Int {
        var t = 40  // system 提示词 + ChatML 角色标记的固定开销
        for ((_, txt) in history) {
            t += txt.length + 6  // 文本长度 + 每条消息的角色/结束标记开销
        }
        return t
    }

    // 生成气泡的圆角灰/蓝底（用户与 AI 共用，便于统一风格）
    private fun bubbleBg(isUser: Boolean): GradientDrawable {
        val gd = GradientDrawable()
        gd.setColor(if (isUser) Color.parseColor("#3F7DFF") else Color.parseColor("#ECECEC"))
        gd.cornerRadius = (12 * resources.displayMetrics.density)
        return gd
    }

    // 生成一条对话气泡（用户=蓝底白字靠右，AI=灰底深字靠左），但不加入容器
    private fun makeBubble(isUser: Boolean): TextView {
        val tv = TextView(this)
        val pad = (8 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad, pad, pad)
        tv.setTextColor(if (isUser) Color.WHITE else Color.DKGRAY)
        tv.background = bubbleBg(isUser)
        tv.gravity = if (isUser) Gravity.END else Gravity.START
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 6, 0, 6)
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
        return tv
    }

    // 生成"思考中"气泡：灰底圆角 + 三个跳动的小圆点（告诉用户 AI 在忙）
    private fun makeThinkingBubble(): LinearLayout {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val dotSize = (26 * resources.displayMetrics.density).toInt()
        val dots = ThinkingDotsView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBg(false)
            setPadding(pad, (pad * 0.6f).toInt(), pad, (pad * 0.6f).toInt())
            addView(dots)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 6, 0, 6)
        lp.gravity = Gravity.START
        container.layoutParams = lp
        return container
    }

    // 立刻把一条消息显示到对话区（带淡入上移动画）
    private fun addMessage(text: String, isUser: Boolean) {
        val tv = makeBubble(isUser)
        tv.text = text
        chatContainer.addView(tv)
        animateIn(tv)
        scrollChatToBottom()
    }

    // 把"思考中"气泡原地换成一条文字气泡（用于加载失败 / 出错 / 空回复）
    private fun replaceThinkingWithText(thinking: View, msg: String) {
        val idx = chatContainer.indexOfChild(thinking)
        chatContainer.removeView(thinking)
        val tv = makeBubble(false)
        tv.text = msg
        if (idx >= 0) chatContainer.addView(tv, idx) else chatContainer.addView(tv)
        animateIn(tv)
        scrollChatToBottom()
    }

    // 气泡出现动画：从下方轻微上移 + 淡入，让"冒字"更顺眼
    private fun animateIn(v: View) {
        v.alpha = 0f
        v.translationY = (12 * resources.displayMetrics.density)
        v.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    // 平滑滚动到底部（替代生硬的瞬间跳转）
    private fun scrollChatToBottom() {
        chatScroll.post {
            val child = chatScroll.getChildAt(0)
            if (child != null) chatScroll.smoothScrollTo(0, child.bottom)
        }
    }

    // 清空对话：历史与界面一起清掉（大模型不卸载，下次直接复用）
    private fun clearChat() {
        history.clear()
        chatContainer.removeAllViews()
    }

    private fun setBusy(b: Boolean) {
        isBusy = b
        safeUi {
            btnSend.isEnabled = !b
            btnClear.isEnabled = !b
        }
    }

}

// "思考中"三点跳动动画：三个小圆点依次上下跳动 + 渐隐渐显，无限循环
// 从容器移除时会自动取消动画（onDetachedFromWindow），不泄漏
class ThinkingDotsView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
    }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init { anim.start() }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = height * 0.13f
        val gap = r * 3f
        val cx0 = width * 0.5f - gap
        for (i in 0..2) {
            // 每个点相位错开 0.18，形成依次跳动
            var t = phase - i * 0.18f
            if (t < 0f) t += 1f
            val s = Math.sin(t * Math.PI).toFloat()
            val dy = -s * (height * 0.22f)
            val alpha = (0.35f + 0.65f * s).coerceIn(0.25f, 1f)
            paint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx0 + i * gap, height * 0.5f + dy, r, paint)
        }
        paint.alpha = 255
    }
}
