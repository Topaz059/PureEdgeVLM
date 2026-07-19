# 9.4 验证 MiniCPM5-1B（关键！）
# 需先安装：py -3.10 -m pip install llama-cpp-python
# 运行：py -3.10 test_llm.py
from llama_cpp import Llama

llm = Llama(
    model_path="C:/Users/Blue/Desktop/work/localai/app/src/main/assets/models/llm/MiniCPM5-1B-Q4_K_M.gguf",  # gguf 实际位置（绝对路径，避免找不到）
    n_ctx=512,
    n_threads=4,
    verbose=False,
)
out = llm("你好，请用一句话介绍你自己。", max_tokens=50, echo=False)
print("LLM 输出:", out["choices"][0]["text"])
print("LLM 验证通过 ✓")
