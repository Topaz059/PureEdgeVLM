#include "llm_engine.h"
#include <llama.h>
#include <vector>
#include <chrono>
#include <android/log.h>

#define LLM_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LLM", __VA_ARGS__)
#define LLM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LLM", __VA_ARGS__)

// 计算字符串中"到最后一个完整 UTF-8 字符为止"的安全长度。
// llama 每个词元解出的字节可能只是一个多字节字符（中文/emoji）的一部分，
// 逐词元回传时必须在完整字符边界切分，否则半个字符送进 JNI 会崩。
// 返回值：可安全发出的前缀字节数；剩下的（不完整字符）应留到下一次。
static size_t utf8SafeLen(const std::string& s) {
    size_t len = s.size();
    if (len == 0) return 0;
    // 从末尾往前，最多回看 4 字节找到某个 UTF-8 首字节
    size_t i = len;
    for (int back = 0; back < 4 && i > 0; back++) {
        i--;
        unsigned char c = (unsigned char)s[i];
        if ((c & 0xC0) != 0x80) {           // 找到首字节（非 10xxxxxx 续字节）
            size_t need;
            if      ((c & 0x80) == 0x00) need = 1;   // 0xxxxxxx
            else if ((c & 0xE0) == 0xC0) need = 2;   // 110xxxxx
            else if ((c & 0xF0) == 0xE0) need = 3;   // 1110xxxx
            else if ((c & 0xF8) == 0xF0) need = 4;   // 11110xxx
            else need = 1;                            // 非法字节，当单字节避免死循环
            return (i + need <= len) ? len : i;       // 末字符完整→全发；否则切在 i
        }
    }
    return len;   // 未找到首字节（异常），全部放行
}

LlmEngine g_llm;

LlmEngine::~LlmEngine() {
    release();
}

bool LlmEngine::load(const std::string& modelPath) {
    std::lock_guard<std::mutex> lock(mtx);
    if (loaded && model) return true;

    struct llama_model_params mparams = llama_model_default_params();
    model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!model) {
        LLM_LOGE("load failed: %s", modelPath.c_str());
        return false;
    }
    vocab = llama_model_get_vocab(model);
    loaded = true;
    LLM_LOGI("load ok: %s", modelPath.c_str());
    return true;
}

bool LlmEngine::isLoaded() const {
    std::lock_guard<std::mutex> lock(mtx);
    return loaded && model != nullptr;
}

void LlmEngine::release() {
    std::lock_guard<std::mutex> lock(mtx);
    if (model) {
        llama_model_free(model);
        model = nullptr;
    }
    vocab = nullptr;
    loaded = false;
}

std::string LlmEngine::generate(const std::string& prompt, int maxTokens,
                                const std::function<void(const std::string&)>& onToken) {
    std::lock_guard<std::mutex> lock(mtx);
    if (!loaded || !model || !vocab) {
        LLM_LOGE("generate: model not loaded");
        return "";
    }
    // 诊断：把完整提示词打出来，确认上游 YOLO/场景/OCR 到底喂了什么给大模型
    LLM_LOGI("prompt: %s", prompt.c_str());

    // 1) 每次生成新建上下文：天然清空 KV cache，免去 llama_memory_clear 调用
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = 2048;   // 上下文窗口：提示词(含历史,~700 token) + 双段生成上限(1280，思考768+答案400) ≈ 1980，留余量取 2048；
                                 // 若 KV 缓存吃紧可降回 1536 并同步把 MainActivity 的 maxTokens 改小（须满足 prompt+maxTokens ≤ n_ctx）
    cparams.n_threads = n_threads;     // 默认 4（骁龙865 大核簇=4）；Benchmark 会改变它
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    struct llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LLM_LOGE("generate: ctx init failed");
        return "";
    }

    // 2) 对 Kotlin 侧拼好的「完整 ChatML 多轮对话」直接分词。
    //    对话的拼装（system/user/assistant 角色标记、句尾 <|im_end|>、以及最后一条
    //    <|im_start|>assistant 前缀）由 Kotlin 负责；这里只做两件事：
    //    ① 句首补 BOS（MiniCPM5 的 chat_template 以 {{- bos_token }} 开头，缺它
    //       模型不进入对话角色）；② 逐 token 贪心解码并流式回传。
    //    MiniCPM5-1B 是标准 ChatML 对话模型（GGUF 自带 chat_template、im_start/im_end
    //    标记），parse_special=true 让 <|im_start|>/<|im_end|> 被识别成真正的特殊词元，
    //    而不是被切成普通子词（若设成 false，ChatML 标记会被当明文，对话会退化）。
    std::vector<llama_token> promptTokens(prompt.size() + 1);
    int nPrompt = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                promptTokens.data(), (int32_t)promptTokens.size(),
                                false, true);
    if (nPrompt < 0) {   // 缓冲不够，按返回值扩容重试
        promptTokens.resize(-nPrompt + 1);
        nPrompt = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(),
                                 promptTokens.data(), (int32_t)promptTokens.size(),
                                 false, true);
    }
    if (nPrompt <= 0) {
        llama_free(ctx);
        LLM_LOGE("generate: tokenize failed");
        return "";
    }
    // 补 BOS（模型 chat_template 开头是 {{- bos_token }}，需要句首标记）
    llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL) {
        promptTokens.insert(promptTokens.begin(), bos);
        nPrompt++;
    }
    promptTokens.resize(nPrompt);
    LLM_LOGI("tokenize ok n=%d (with bos, chatml from kotlin)", nPrompt);

    // 3) 把提示词整批喂进去（只在最后一个词取 logits）
    auto t0 = std::chrono::steady_clock::now();
    struct llama_batch batch = llama_batch_init((int32_t)nPrompt, 0, 1);
    for (int32_t i = 0; i < nPrompt; i++) {
        batch.token[i]     = promptTokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == nPrompt - 1) ? 1 : 0;
    }
    batch.n_tokens = nPrompt;   // 关键：llama_batch_init 把 n_tokens 初始化成 0，必须手动设回实际 token 数
    if (llama_decode(ctx, batch) < 0) {
        llama_batch_free(batch);
        llama_free(ctx);
        LLM_LOGE("generate: prompt decode failed");
        return "";
    }
    auto t1 = std::chrono::steady_clock::now();
    LLM_LOGI("prefill ok n=%d, prefill_ms=%lld, generating...", nPrompt,
             (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::string result;
    std::string pending;           // UTF-8 缓冲：暂存尚未凑成完整字符的尾部字节
    llama_token prevToken = -1;
    int repeatCount = 0;
    int logitsIdx = nPrompt - 1;   // 第一批在末词取；之后每次单 token 取 index 0
    int curPos = (int)nPrompt;
    int genTok = 0;                // 生成的词元计数（循环外统计，用于算速度）

    // 双段软上限：思考与回答各占一块，保证答案一定能生成（防"说一半就停"）
    const int MAX_THINK_TOKENS  = 768;   // 思考阶段最多生成的词元数（第三轮实测"你有什么功能"思考就超了 256，给 3 倍余量）
    const int MAX_ANSWER_TOKENS = 400;   // 回答阶段最多生成的词元数（保证答案一定能拿到足额词元）
    bool seenThinkEnd = false;           // 是否已遇到 </think>
    int  thinkTok = 0;
    int  answerTok = 0;

    for (int i = 0; i < maxTokens; i++) {
        float* logits = llama_get_logits_ith(ctx, logitsIdx);
        if (!logits) break;

        // 贪心解码：取概率最高的词元
        llama_token next = 0;
        float best = -1e30f;
        for (int v = 0; v < n_vocab; v++) {
            if (logits[v] > best) { best = logits[v]; next = (llama_token)v; }
        }
        if (llama_vocab_is_eog(vocab, next)) break;

        // token -> 文字（UTF-8 中文）
        char pieceBuf[256];
        int n = llama_token_to_piece(vocab, next, pieceBuf, sizeof(pieceBuf), 0, false);
        if (n > 0) {
            result.append(pieceBuf, (size_t)n);
            // 逐词元回传前，先在 UTF-8 完整字符边界切分：完整部分发出，半个字符留到下次，
            // 避免把不完整的中文/emoji 字节送进 JNI 造成崩溃
            pending.append(pieceBuf, (size_t)n);
            size_t safe = utf8SafeLen(pending);
            if (safe > 0 && onToken) {
                onToken(pending.substr(0, safe));
                pending.erase(0, safe);
            }
        }
        // 诊断：每 15 个 token 打一行进度，便于区分"慢"还是"真卡死"
        if (i % 15 == 0) {
            size_t show = (size_t)std::min((size_t)24, result.size());
            LLM_LOGI("gen i=%d len=%zu tail='%.*s'", i, result.size(), (int)show, result.c_str());
        }

        // 退化保护：连续重复同一词元 >=8 次强制停（防大模型卡空转圈）
        if (next == prevToken) { if (++repeatCount >= 8) break; }
        else repeatCount = 0;
        prevToken = next;

        // —— 双段软上限状态机（防"说一半就停下"）——
        // 每生成一个词元就推进本段计数：在模型吐出 </think> 之前算"腹稿（思考）"，
        // 之后算"正式答案"。两段各有独立上限，保证答案段一定能拿到足额词元，
        // 不会出现"腹稿把额度吃光、答案一个字都没吐就停"的情况。
        if (!seenThinkEnd && result.find("</think>") != std::string::npos) {
            seenThinkEnd = true;   // 模型自己写完了腹稿（出现 </think>），切到答案段
        }
        if (!seenThinkEnd) {
            if (++thinkTok >= MAX_THINK_TOKENS) {
                // 腹稿写太久了（超过上限）：强制切到答案段，好让答案一定有机会生成，
                // 而不是耗在里面把整段额度吃完、最后答案啥都没有。
                seenThinkEnd = true;
            }
        } else {
            if (++answerTok >= MAX_ANSWER_TOKENS) {
                break;             // 答案段写够了，主动收尾（防模型无限续写）
            }
        }

        // 把新词元喂回去，位置 +1
        batch.n_tokens     = 1;
        batch.token[0]     = next;
        batch.pos[0]       = (llama_pos)curPos;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        if (llama_decode(ctx, batch) < 0) break;
        logitsIdx = 0;
        curPos++;
        genTok++;
    }

    // 循环结束后，若缓冲里还剩完整字符（正常结束一般为空），补发出去
    if (!pending.empty() && onToken && utf8SafeLen(pending) == pending.size()) {
        onToken(pending);
        pending.clear();
    }

    llama_batch_free(batch);
    llama_free(ctx);
    auto t2 = std::chrono::steady_clock::now();
    double genSec = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count() / 1000.0;
    LLM_LOGI("generate done len=%zu gen_tok=%d gen_sec=%.1f speed=%.1f tok/s text=%s",
             result.size(), genTok, genSec, genSec > 0 ? (genTok / genSec) : 0.0, result.c_str());
    return result;
}
