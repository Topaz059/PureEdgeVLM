# MiniCPM5-1B 核实结论（2026-07-19 实测 + 官方核实）

> 用途：阶段一第10步产物，供简历 / 面试引用。结论以官方来源为准。

## 模型基本信息（来源：HuggingFace 官方模型卡 openbmb/MiniCPM5-1B）
- 类型：Causal Language Model（因果语言模型）
- 架构：**Standard LlamaForCausalLM（标准 Llama 架构）**，无需定制内核，兼容主流推理引擎（llama.cpp / Ollama / LM Studio / vLLM / SGLang 等）
- 参数量：1,080,632,832（约 1.08B），非嵌入参数 679,552,512
- 层数：24 层
- 注意力：GQA，Q 头 16、KV 头 2
- 上下文长度：131,072（128K）
- 发布：2026.05.19（MiniCPM5 系列首个 checkpoint）
- 开源协议：Apache-2.0

## ⚠️ 重要更正（本项目原文档有误）
- 原实施方案 / 教学文档多处写"MiniCPM5 使用 SALA 混合注意力架构，需 llama.cpp b3500+ 支持"——**这是错的**。
- 事实：MiniCPM5-1B 是 **Standard LlamaForCausalLM** 标准架构，不是 SALA；SALA（MiniCPM-SALA）是另一个独立模型（2026.02.11 发布），与 MiniCPM5-1B 无关。
- 因此：llama.cpp 对其为标准支持，无需死守某个特定 bXXXX commit，也没有"定制内核"需求。
- 实测验证：llama-cpp-python 0.3.34（捆绑较新 llama.cpp）已成功加载 MiniCPM5-1B Q4_K_M 并生成通顺中文（2026-07-19，test_llm.py 通过）。

## 量化
- 官方提供 GGUF 版（MiniCPM5-1B-GGUF），用于 llama.cpp / Ollama / LM Studio
- 本项目使用 **Q4_K_M**（4 比特，约 0.5GB）：官方推荐，质量 / 速度平衡最好
- 注意：别用 Q2_K（会乱码）；别用 Q8（太大，手机撑不住）

## 手机端使用约定
- 上下文窗口：手机端只用 2048，不用满 128K（省内存，KV cache 更小）
- 内存估算：Q4_K_M 权重 ~0.5GB + KV cache(2048) ~200MB，合计 ~650MB 级

## 来源
- HuggingFace：https://huggingface.co/openbmb/MiniCPM5-1B （官方模型卡，Architecture: Standard LlamaForCausalLM）
- ModelScope：https://modelscope.cn/models/OpenBMB/MiniCPM5-1B
