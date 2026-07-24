#ifndef PUREEDGE_LLM_ENGINE_H
#define PUREEDGE_LLM_ENGINE_H

#include <string>
#include <functional>
#include <mutex>
#include <llama.h>   // 引入 llama_token / llama_context / llama_memory_t 等类型（m_cached_prefix 用到 llama_token 完整体）

// 大模型推理封装：加载 GGUF（MiniCPM5-1B Q4_K_M）→ 贪心解码出中文 → 释放。
// 接口基于 llama.cpp 当前（master）C API 编写，已对照官方 llama.h 核实。
class LlmEngine {
public:
    LlmEngine() = default;
    ~LlmEngine();

    // 从绝对路径加载模型；成功返回 true。线程安全。
    bool load(const std::string& modelPath);
    bool isLoaded() const;
    void release();

    // 生成：prompt 须是 Kotlin 已拼好的「完整 ChatML 多轮对话」
    // （含 system/user/assistant 角色标记与末尾 <|im_start|>assistant 前缀），
    // 引擎只补 BOS 并逐 token 解码，每解出一段文字通过 onToken 回调（UTF-8 片段）。
    // reuse=true（默认，聊天用）：复用常驻上下文的 KV 缓存，只追加本轮新内容，多轮首字更快；
    // reuse=false（Benchmark 用）：每轮新建上下文，保证测速干净。
    // 返回完整文本。线程安全（内部互斥锁串行化，防连点并发崩）。
    std::string generate(const std::string& prompt, int maxTokens,
                        const std::function<void(const std::string&)>& onToken,
                        bool reuse = true);

    // 设置推理线程数（Benchmark 用），范围 1~8；默认 4（骁龙865 大核簇=4）
    void setNumThreads(int n) { if (n > 0) n_threads = n; }

    // 思维链开关：true=模型先吐 <think>...</think> 再答（默认）；
    // false=直接出答案（关掉后 C++ 不再给"思考段"预留 token 预算，并把答案预算放大）。
    void setThinkingEnabled(bool b) { thinking_enabled = b; }

    // KV 缓存复用总开关：true=聊天复用常驻上下文（默认，多轮首字更快）；
    // false=每轮整段重算（退回原行为，可用于对比/排查复用问题）。Benchmark 仍强制不复用。
    void setKvReuse(bool b) { m_reuse_kv = b; }

private:
    struct llama_model*        model = nullptr;
    const struct llama_vocab*  vocab = nullptr;
    mutable std::mutex         mtx;
    int                        n_threads = 4;
    bool                       loaded = false;
    bool                       thinking_enabled = true;   // 默认开思维链；关掉=直接答
    bool                       m_reuse_kv = true;         // KV 缓存复用总开关（运行时可调）；false=每轮整段重算

    struct llama_context*      m_ctx = nullptr;          // 常驻上下文（复用 KV 缓存）；为空则需新建
    std::vector<llama_token>   m_cached_prefix;           // 上一轮输入的 token 序列，用于算公共前缀、决定增量解码起点
    int                        m_n_ctx = 2048;           // 上下文窗口大小（溢出时放弃复用、整段重算）
};

// 全局单例（native_bridge.cpp 与 llm_engine.cpp 共用）
extern LlmEngine g_llm;

#endif // PUREEDGE_LLM_ENGINE_H
