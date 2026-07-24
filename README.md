<div align="center">

# PureEdgeVLM

**在一台 2020 年的骁龙 865 手机上，纯 CPU 跑通「目标检测 + 场景识别 + OCR + 本地大模型对话」四模型端侧多模态系统 —— 全程离线，零网络依赖。**

![Platform](https://img.shields.io/badge/platform-Android%20arm64--v8a-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin%20%2B%20C%2B%2B17-blue?logo=kotlin&logoColor=white)
![Inference](https://img.shields.io/badge/inference-NCNN%20%2B%20llama.cpp-orange)
![Device](https://img.shields.io/badge/device-Snapdragon%20865%20(CPU%20only)-red)
![Offline](https://img.shields.io/badge/network-100%25%20offline-lightgrey)

[功能亮点](#-功能亮点) · [性能实测](#-性能实测骁龙-865纯-cpu) · [架构设计](#-架构设计) · [快速开始](#-快速开始) · [模型下载](#2⃣-下载模型权重必做仓库不含权重) · [工程踩坑精选](#-工程踩坑精选) · [致谢](#-致谢)

</div>

---

## 📖 这是什么

PureEdgeVLM 是一个**纯端侧多模态理解 Android App**：拍照或选图后，手机本地同时运行三个视觉模型完成"看懂图片"（有什么物体、在什么场景、有什么文字），再由本地大模型进行多轮中文对话——**所有推理都在手机 CPU 上完成，不需要联网，不上传任何数据**。

| 模型 | 任务 | 框架 | 大小 |
| --- | --- | --- | --- |
| YOLOv11s | 目标检测（80 类 COCO） | NCNN | ~38 MB (fp32) |
| ResNet50-Places365 | 场景识别（365 类） | NCNN | ~97 MB |
| PP-OCRv5 mobile | 中英文文字识别 | NCNN | ~11 MB |
| MiniCPM5-1B (Q4_K_M) | 本地大模型多轮对话 | llama.cpp | ~657 MB |

> **为什么值得一看**：不依赖 GPU/NPU/DSP，只用 llama.cpp + NCNN 两个推理框架，在 3MB L3 缓存的老旗舰上把四个模型的调度、内存、线程分配抠到能实用的程度。所有性能数字都是真机实测，测试条件全部公开。

## ✨ 功能亮点

- **📷 实时相机检测** — CameraX 实时预览 + 检测框叠加，预览/分析双路 4:3 对齐（框不偏），场景结果 500ms 缓存复用，流畅不掉帧
- **🖼 图片识别** — 相册选图，三视觉模型**并行推理**（CountDownLatch 三线程），比串行快 34%
- **💬 本地大模型对话** — MiniCPM5-1B 流式输出、多轮记忆、KV 缓存前缀复用降低多轮首字延迟、可开关思维链
- **⏱ Benchmark 页** — 一键跑 4 模型 × 多线程数测速矩阵，结果导出 CSV，仓库自带 Python 脚本一键转 Markdown 报表
- **🛡 健壮性** — 启动时模型完整性自检（缺哪个提示哪个，不阻断其它功能）、推理全链路异常兜底、防连点、防 Activity 销毁后崩溃

四页底部导航：**对话 / 识别 / 相机 / Benchmark**，纯 Android View 实现（非 Compose）。

## 📊 性能实测（骁龙 865，纯 CPU）

> 测试条件：Snapdragon 865（1×A77@2.84 + 3×A77@2.42 + 4×A55），NCNN 关闭 Vulkan，fp32 权重，640×640 输入，每组跑 10 次取平均（首跑 warmup 不计）。原始数据见 [`models_workspace/benchmark.csv`](models_workspace/benchmark.csv) 与 [`recognition_bench_serial.csv`](recognition_bench_serial.csv) / [`recognition_bench_parallel.csv`](recognition_bench_parallel.csv)。

### 单模型延迟（最优线程配置）

| 模型 | 最优线程数 | 平均延迟 | 吞吐 |
| --- | --- | --- | --- |
| YOLOv11s 目标检测 | 4 | 155.2 ms | 6.4 fps |
| ResNet50 场景识别 | 4 | 45.8 ms | 21.8 fps |
| PP-OCRv5 det+rec | 自动 | 148.1 ms | 6.8 fps |
| MiniCPM5-1B（128 tok） | 8 | 6319.6 ms | — |

### 视觉流水线：串行 → 三模型并行（核心优化）

| 指标 | 串行 | 并行 | 变化 |
| --- | --- | --- | --- |
| **流水线总耗时** | **503.2 ms** | **331.5 ms** | **-34.1%** ✅ |
| YOLO 单项耗时 | 199.4 ms | 308.5 ms | +54.7% |
| 场景识别单项耗时 | 159.0 ms | 245.9 ms | +54.7% |
| OCR 单项耗时 | 144.8 ms | 287.2 ms | +98.3% |

这组数据本身就是最有意思的结论：**并行时每个模型单独变慢了（大小核与缓存争抢），但总耗时 = 三者重叠取最慢者，而不是相加——净收益仍然是 -34%**。线程分配为 YOLO=4 / OCR=3 / 场景=1，贴合 865 的"1+3+4"三丛集结构。

## 🏗 架构设计

```
┌───────────────────────────────────────────────────────┐
│  Kotlin UI 层（Android View）                          │
│  对话页 │ 识别页 │ 相机页(CameraX) │ Benchmark 页       │
├───────────────────────────────────────────────────────┤
│  JNI Bridge（NativeBridge.kt）                         │
│  yoloDetect │ sceneRecognize │ ocrRecognize            │
│  llmGenerate(流式回调) │ benchmarkRun │ modelStatus     │
├───────────────────────────────────────────────────────┤
│  C++17 核心（libpureedgevlm.so）                       │
│  YoloDetector │ SceneClassifier │ OcrRecognizer        │
│  LlmEngine(KV缓存复用) │ 三模型并行调度                 │
├───────────────────────────────────────────────────────┤
│  推理框架                                              │
│  NCNN（静态链接，Vulkan OFF） │ llama.cpp（源码子目录）  │
└───────────────────────────────────────────────────────┘
```

**调度策略（针对骁龙 865 硬件特性）**：

- **视觉三模型并行**：三线程 CountDownLatch 并发，绑大核组（`set_cpu_powersave(2)`），线程配额 YOLO=4 / OCR=3 / 场景=1
- **LLM 异步单线程解码**：大模型解码是访存密集型，不参与视觉并行，避免把 3MB L3 缓存打爆
- **多轮对话 KV 缓存前缀复用**：聊天上下文常驻，只对新增 token 做 prefill，显著降低多轮首字延迟（设置页可关闭）

## 🚀 快速开始

### 环境要求

- Android Studio（含 NDK r26c+、CMake 3.22+）
- Python 3.10+（用于导出/转换模型）
- 一台 arm64-v8a 安卓手机（Android 9+，建议 6GB+ 内存；骁龙 865 为验证基准机型）

### 1️⃣ 克隆仓库

```bash
git clone https://github.com/Topaz059/PureEdgeVLM.git
cd PureEdgeVLM
```

### 2️⃣ 下载模型权重（必做，仓库不含权重）

> ⚠️ 模型权重共约 **800 MB**，未放入 git（`.gitignore` 排除）。请按下面步骤获取，并放到对应目录：

```
app/src/main/assets/models/
├── yolo/    model.ncnn.param + model.ncnn.bin
├── scene/   resnet50_fp32.param + resnet50_fp32.bin + categories_places365.txt
├── ocr/     PP_OCRv5_mobile_det.ncnn.param/.bin + PP_OCRv5_mobile_rec.ncnn.param/.bin
└── llm/     MiniCPM5-1B-Q4_K_M.gguf
```

<details>
<summary><b>① YOLOv11s（目标检测，~38 MB）— 一条命令导出</b></summary>

```bash
pip install ultralytics
yolo export model=yolo11s.pt format=ncnn imgsz=640 simplify=True
```

产出 `yolo11s_ncnn_model/model.ncnn.param` + `model.ncnn.bin`，复制到 `app/src/main/assets/models/yolo/`。

> ⚠️ **两个必踩的坑（实机验证过）**：
> 1. **必须 fp32**（导出默认即是）。不要用 `ncnnoptimize` 压 fp16 —— 骁龙 865 上 YOLO 的 fp16 权重检测头输出全零。
> 2. **必须走 ultralytics 原生导出（PNNX 路径）**。不要手动 `onnx2ncnn` 转 yolo11s —— 会把权重静默转坏，模型能加载但一个目标都检不出。

</details>

<details>
<summary><b>② ResNet50-Places365（场景识别，~97 MB）— 下载官方权重后转换</b></summary>

```bash
# 1. 下载 MIT CSAIL 官方权重与标签
wget http://places2.csail.mit.edu/models_places365/resnet50_places365.pth.tar
wget https://raw.githubusercontent.com/csailvision/places365/master/categories_places365.txt

# 2. PyTorch → ONNX（仓库自带脚本）
python models_workspace/places365/export_resnet50_places365_to_onnx.py

# 3. ONNX → NCNN（需要编译过 NCNN 的 onnx2ncnn 工具，见下方 third_party 说明）
onnx2ncnn resnet50_places365_sim.onnx resnet50_fp32.param resnet50_fp32.bin
```

把 `resnet50_fp32.param`、`resnet50_fp32.bin`、`categories_places365.txt` 三个文件放入 `app/src/main/assets/models/scene/`。

> 注意：`.pth.tar` 不是压缩包，别解压；新版 NCNN 的 `tools/CMakeLists.txt` 默认不编 onnx2ncnn，需手动在末尾加 `add_subdirectory(onnx)`。

</details>

<details>
<summary><b>③ PP-OCRv5 mobile（文字识别，~11 MB）— 直接取现成文件</b></summary>

```bash
git clone https://github.com/equationl/ncnn-android-ppocrv5.git
```

从该仓库 `app/src/main/assets/` 复制这 4 个 **mobile** 开头的文件到 `app/src/main/assets/models/ocr/`：

- `PP_OCRv5_mobile_det.ncnn.param` / `.bin`
- `PP_OCRv5_mobile_rec.ncnn.param` / `.bin`

> 只用 `mobile` 版；`server` 版单文件 80MB+，手机跑不动。

</details>

<details>
<summary><b>④ MiniCPM5-1B（本地大模型，~657 MB）— ModelScope 或 HuggingFace</b></summary>

```bash
# 国内推荐 ModelScope
pip install modelscope
modelscope download --model OpenBMB/MiniCPM5-1B-GGUF MiniCPM5-1B-Q4_K_M.gguf --local_dir ./models/minicpm5
```

复制 `MiniCPM5-1B-Q4_K_M.gguf` 到 `app/src/main/assets/models/llm/`。

> 文件名大小写敏感（`MiniCPM5-1B-Q4_K_M.gguf`），写错会 404。备选：[HuggingFace openbmb/MiniCPM5-1B-GGUF](https://huggingface.co/openbmb/MiniCPM5-1B-GGUF)。必须 Q4_K_M 量化（Q2_K 会输出乱码）。

</details>

### 3️⃣ 准备第三方推理库（必做，仓库同样不含）

`app/src/main/cpp/third_party/` 也被 `.gitignore` 排除，需要自己放三样东西：

| 目录 | 内容 | 获取方式 |
| --- | --- | --- |
| `third_party/ncnn/` | NCNN Android arm64 **静态库**（include + lib） | clone [Tencent/ncnn](https://github.com/Tencent/ncnn)，按官方文档编 Android arm64-v8a，**务必 `-DNCNN_VULKAN=OFF`**，把安装产物（`include/`、`lib/`）拷入 |
| `third_party/llama.cpp/` | llama.cpp **完整源码**（CMake 以 `add_subdirectory` 引入） | `git clone https://github.com/ggml-org/llama.cpp.git`（2026-07 的 master 验证可用） |
| `third_party/opencv-mobile-4.13.0-android/` | opencv-mobile 预编译包（OCR 依赖） | 去 [nihui/opencv-mobile Releases](https://github.com/nihui/opencv-mobile/releases) 下载 **opencv-mobile-4.13.0-android**（注意版本，5.x 头文件结构不兼容） |

### 4️⃣ 编译安装

用 Android Studio 打开工程 → 连接手机（开启 USB 调试）→ Run 'app'。

或命令行：

```bash
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/
```

启动后 App 会自检模型文件完整性，缺哪个会在界面上黄字提示（不影响其它功能使用）。

## 📂 目录结构

```
PureEdgeVLM/
├── app/src/main/
│   ├── java/com/topaz/pureedgevlm/    # Kotlin：四页 UI + JNI 声明
│   │   ├── MainActivity.kt            #   对话页（LLM 流式多轮对话）
│   │   ├── ImageActivity.kt           #   识别页（选图 + 三模型并行）
│   │   ├── CameraActivity.kt          #   相机页（实时检测叠框）
│   │   ├── BenchmarkActivity.kt       #   Benchmark 页（测速矩阵 + CSV）
│   │   └── NativeBridge.kt            #   JNI 桥
│   ├── cpp/                           # C++17 推理核心
│   │   ├── yolo_detector.cpp          #   YOLO 解码 + NMS
│   │   ├── scene_classifier.cpp       #   Places365 分类
│   │   ├── ocr/ppocrv5.cpp            #   OCR det→rec 流水线
│   │   ├── llm_engine.cpp             #   llama.cpp 封装（KV 缓存复用）
│   │   ├── native_bridge.cpp          #   JNI 实现 + 并行调度
│   │   └── third_party/               #   ⬅ 需自备（见上文 3️⃣）
│   └── assets/models/                 #   ⬅ 需自备（见上文 2️⃣）
├── models_workspace/                  # PC 端脚本：模型验证/导出/测速报表
│   ├── test_yolo.py / test_ocr.py / test_llm.py
│   ├── places365/export_resnet50_places365_to_onnx.py
│   ├── benchmark.md                   # 真机测速矩阵（4 模型 × 线程数）
│   └── benchmark_to_markdown.py       # CSV → Markdown 报表
└── recognition_bench_*.csv            # 串行 vs 并行 原始实测数据
```

## 🕳 工程踩坑精选

这个项目大部分时间花在了"能跑"到"跑对、跑快"之间。几个最值钱的坑：

| 坑 | 现象 | 结论 |
| --- | --- | --- |
| 骁龙 865 + YOLO fp16 | 检测头输出全零，不报错 | YOLO 权重必须 fp32 |
| onnx2ncnn 转 yolo11s | 权重静默转坏，0 检出 | 必须用 ultralytics 原生 NCNN 导出（PNNX） |
| NCNN `substract_mean_normalize` | 场景识别永远输出同一类且 prob=1.0 | 它是乘法 `(x-mean)*norm`，需 `mean*=255`、`norm=1/(255*std)` |
| `llama_batch_get_one` + `llama_batch_free` | 安卓 MTE 直接 SIGABRT | `get_one` 的 batch 绝不能 free |
| 多轮对话 KV 前缀复用 | 前缀对不上时答非所问 | 前缀必须逐字节一致，历史裁剪需同步清缓存 |
| opencv-mobile 与 NDK 的 OpenMP ABI 错配 | 打开即闪退，普通 logcat 无栈 | 新符号 `__kmpc_dispatch_deinit` 用空实现补，不能转发老函数 |
| 三模型并行"反直觉" | 并行后每个模型都变慢了 | 总耗时看重叠不看单项，实测仍 -34% |

更多细节埋在源码注释里。

## 🗺 Roadmap

- [x] 四模型端侧跑通（检测 / 场景 / OCR / LLM）
- [x] 视觉三模型并行调度（-34% 实测）
- [x] LLM KV 缓存复用 + 思维链开关
- [x] 实时相机页 + Benchmark 测速矩阵
- [ ] 模型瘦身 / 按需懒加载
- [ ] 更多机型适配验证

## 🙏 致谢

本项目站在这些优秀开源项目的肩膀上：

- [Tencent/ncnn](https://github.com/Tencent/ncnn) — 端侧推理框架
- [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) — 大模型端侧推理
- [ultralytics](https://github.com/ultralytics/ultralytics) — YOLOv11
- [CSAILVision/places365](https://github.com/CSAILVision/places365) — Places365 官方权重
- [equationl/ncnn-android-ppocrv5](https://github.com/equationl/ncnn-android-ppocrv5) — PP-OCRv5 NCNN 安卓移植
- [OpenBMB/MiniCPM](https://github.com/OpenBMB) — MiniCPM5-1B
- [nihui/opencv-mobile](https://github.com/nihui/opencv-mobile) — 精简版 OpenCV

---

<div align="center">

如果这个项目对你研究端侧部署有帮助，欢迎点个 ⭐

</div>
