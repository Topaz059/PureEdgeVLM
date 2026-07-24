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
    if (m_ctx) {
        llama_free(m_ctx);
        m_ctx = nullptr;
    }
    m_cached_prefix.clear();
    if (model) {
        llama_model_free(model);
        model = nullptr;
    }
    vocab = nullptr;
    loaded = false;
}

std::string LlmEngine::generate(const std::string& prompt, int maxTokens,
                                const std::function<void(const std::string&)>& onToken,
                                bool reuse) {
    std::lock_guard<std::mutex> lock(mtx);
    if (!loaded || !model || !vocab) {
        LLM_LOGE("generate: model not loaded");
        return "";
    }
    // 诊断：把完整提示词打出来，确认上游 YOLO/场景/OCR 到底喂了什么给大模型
    LLM_LOGI("prompt: %s", prompt.c_str());

    // 1) 对 Kotlin 侧拼好的「完整 ChatML 多轮对话」直接分词。
    //    对话的拼装（system/user/assistant 角色标记、句尾 <|im_end|>、以及最后一条
    //    <|im_start|>assistant 前缀）由 Kotlin 负责；这里只做分词 + 解码。
    //    MiniCPM5-1B 是标准 ChatML 对话模型，parse_special=true 让 <|im_start|>/<|im_end|>
    //    被识别成真正的特殊词元（设 false 会被当明文，对话退化）。
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

    // 2) 决定是否复用常驻上下文（KV 缓存）。
    //    reuse=true（聊天）：常驻一个上下文，本轮只把"新增的那句"追加进缓存，
    //    前面的历史不用重算 → 多轮首字延迟大幅下降。
    //    reuse=false（Benchmark）：每轮新建、用完销毁，保证测速干净（与原行为一致）。
    //    增量解码起点 startPos = 新 prompt 与已缓存 prompt 的最长公共前缀长度 L：
    //      - 正常多轮：新 prompt = 旧 prompt + 新一句，L = 旧 prompt 长度；
    //      - 清空对话后：新 prompt 变短，L 退化为公共的 system 前缀（甚至 0），自动重置。
    //    删掉缓存里 [L, 末尾) 的旧内容（主要是上一轮生成的回答，prompt 之后多余的部分），
    //    只保留前缀 [0,L)，再解码 [L, nPrompt) 这一小段新内容即可。
    int startPos = 0;
    bool useCache = (reuse && m_reuse_kv) && (m_ctx != nullptr);
    if (useCache) {
        size_t L = 0;
        size_t minLen = (promptTokens.size() < m_cached_prefix.size())
                            ? promptTokens.size() : m_cached_prefix.size();
        while (L < minLen && promptTokens[L] == m_cached_prefix[L]) L++;

        // 本回合 prompt + 生成会超出上下文窗口 → 放弃复用、整段重算，防 KV 缓存溢出
        if (nPrompt + maxTokens > m_n_ctx) {
            LLM_LOGI("kv cache would overflow (nPrompt=%d+max=%d > n_ctx=%d), reset", nPrompt, maxTokens, m_n_ctx);
            useCache = false;
        } else {
            llama_memory_t mem = llama_get_memory(m_ctx);
            if (mem) llama_memory_seq_rm(mem, 0, (llama_pos)L, -1);  // p1<0 表示 [L, 末尾)
            startPos = (int)L;
            LLM_LOGI("kv reuse: common prefix L=%zu, will decode %d new tokens", L, nPrompt - startPos);
        }
    }

    if (!useCache) {
        // 不复用：销毁旧上下文，新建一个（与原来每轮新建的行为一致）
        if (m_ctx) { llama_free(m_ctx); m_ctx = nullptr; }
        m_cached_prefix.clear();
        struct llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx     = 2048;   // 上下文窗口：提示词(含历史) + 双段生成上限(1280) 留余量；
                                    // 若 KV 缓存吃紧可降回 1536 并同步把 MainActivity 的 maxTokens 改小
        cparams.n_threads = n_threads;     // 默认 4（骁龙865 大核簇=4）；Benchmark 会改变它
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        m_ctx = llama_init_from_model(model, cparams);
        if (!m_ctx) {
            LLM_LOGE("generate: ctx init failed");
            return "";
        }
        m_n_ctx = 2048;
        startPos = 0;
    }

    // 3) 把"需要新算的那段"喂进去（startPos=0 时即整段；复用时仅新追加的一句），末词取 logits
    auto t0 = std::chrono::steady_clock::now();
    int nDelta = nPrompt - startPos;
    struct llama_batch batch = llama_batch_init((int32_t)nDelta, 0, 1);
    for (int32_t i = startPos; i < nPrompt; i++) {
        batch.token[i - startPos]     = promptTokens[i];
        batch.pos[i - startPos]       = i;
        batch.n_seq_id[i - startPos]  = 1;
        batch.seq_id[i - startPos][0] = 0;
        batch.logits[i - startPos]    = (i == nPrompt - 1) ? 1 : 0;
    }
    batch.n_tokens = nDelta;   // llama_batch_init 把 n_tokens 初始化成 0，必须手动设回实际 token 数
    if (llama_decode(m_ctx, batch) < 0) {
        llama_batch_free(batch);
        if (m_ctx) { llama_free(m_ctx); m_ctx = nullptr; }
        m_cached_prefix.clear();
        LLM_LOGE("generate: prompt decode failed");
        return "";
    }
    auto t1 = std::chrono::steady_clock::now();
    LLM_LOGI("prefill ok n=%d (delta=%d), prefill_ms=%lld, generating...", nPrompt, nDelta,
             (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::string result;
    std::string pending;           // UTF-8 缓冲：暂存尚未凑成完整字符的尾部字节
    llama_token prevToken = -1;
    int repeatCount = 0;
    int logitsIdx = nDelta - 1;    // 第一批取「刚解码这一批的最后一个词」的 logits；
                                   // 注意 llama_get_logits_ith 的参数是「批内下标」(0..nDelta-1)，不是绝对位置！
                                   // 完整 prefill 时 nDelta==nPrompt，与旧的 nPrompt-1 等价；
                                   // 但复用 KV 时 nDelta 只是新增那一段(如 17)，必须用 nDelta-1，
                                   // 否则会去取下标 113(实际批只有 17 个词元)→ 越界返回 NULL → 直接 break 空答。
                                   // 之后每步单 token 解码，下标恒为 0（见下方 logitsIdx=0）。
    int curPos = (int)nPrompt;
    int genTok = 0;                // 生成的词元计数（循环外统计，用于算速度）
    std::vector<llama_token> genVec;  // 收集本轮生成的词元，末尾并入 m_cached_prefix（缓存全量=输入+回答）

    // 双段软上限：思考与回答各占一块，保证答案一定能生成（防"说一半就停"）
    const int MAX_THINK_TOKENS  = 768;   // 思考阶段最多生成的词元数（第三轮实测"你有什么功能"思考就超了 256，给 3 倍余量）
    const int MAX_ANSWER_TOKENS = thinking_enabled ? 400 : 1024;  // 关思维链后答案预算放大，长回答不被截断
    bool seenThinkEnd = !thinking_enabled;   // 关思维链=已"想完"，生成直接进答案段，不再给思考段留预算
    int  thinkTok = 0;
    int  answerTok = 0;

    for (int i = 0; i < maxTokens; i++) {
        float* logits = llama_get_logits_ith(m_ctx, logitsIdx);
        if (!logits) break;

        // 贪心解码：取概率最高的词元
        llama_token next = 0;
        float best = -1e30f;
        for (int v = 0; v < n_vocab; v++) {
            if (logits[v] > best) { best = logits[v]; next = (llama_token)v; }
        }
        if (llama_vocab_is_eog(vocab, next)) break;
        genVec.push_back(next);   // 记录生成的词元，供末尾写回 m_cached_prefix（= prompt+回答）

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
        if (llama_decode(m_ctx, batch) < 0) break;
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

    // 把"本轮输入 prompt + 本轮生成的回答"整段存为缓存前缀，供下一轮算公共前缀。
    // 关键点：KV 缓存里实际装的是"prompt 全文 + 刚生成的这串回答词元"。
    // 只有把回答词元（genVec）也并入 m_cached_prefix，下一轮算出的公共前缀 L 才会
    // 等于缓存里真正的长度——否则 L 只算到 prompt 末尾，llama_memory_seq_rm 会把
    // 缓存里"当前 prompt 之后那部分"误删为 0，导致缓存错位、模型直接吐结束符（空答）。
    // 不复用（Benchmark）则销毁上下文，行为和原来每轮新建一致。
    m_cached_prefix = promptTokens;
    m_cached_prefix.insert(m_cached_prefix.end(), genVec.begin(), genVec.end());
    if (!reuse) {
        llama_free(m_ctx);
        m_ctx = nullptr;
        m_cached_prefix.clear();
    }

    auto t2 = std::chrono::steady_clock::now();
    double genSec = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count() / 1000.0;
    LLM_LOGI("generate done len=%zu gen_tok=%d gen_sec=%.1f speed=%.1f tok/s text=%s",
             result.size(), genTok, genSec, genSec > 0 ? (genTok / genSec) : 0.0, result.c_str());
    return result;
}
