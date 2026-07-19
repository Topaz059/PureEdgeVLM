# PureEdgeVLM

在手机（骁龙 865，8GB 内存）上跑的端侧多模态视觉系统：YOLOv11n 做物体检测 + ResNet50 Places365 做场景识别 + PP-OCRv5 做文字识别 + MiniCPM5-1B 做本地大模型对话。所有模型都在手机本地跑，不联网。

## 本仓库包含什么

- 安卓 App 源码（Android Studio Native C++ 模板）
- 第 9 步的 Python 验证脚本：`models_workspace/test_yolo.py`、`test_ocr.py`、`test_llm.py`，以及 `models_workspace/places365/predict_places365.py`（场景识别）
- 阶段一教程文档：`阶段一_环境搭建与模型转换_手把手教学.md` 等

## ⚠️ 本仓库【没有】模型权重文件

模型文件总共约 700MB，太大，没有放进 git（用 `.gitignore` 排除了）。你需要按下面步骤自己下载 / 导出，再放进项目里，App 才能跑起来。

没推上来的文件清单：

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| `MiniCPM5-1B-Q4_K_M.gguf` | 约 657MB | 本地大模型（LLM） |
| `PP_OCRv5_mobile_det.ncnn.param` + `.bin` | 约 2.3MB | OCR 检测 |
| `PP_OCRv5_mobile_rec.ncnn.param` + `.bin` | 约 7.9MB | OCR 识别 |
| `model.ncnn.param` + `.bin`（YOLOv11n） | 约 11MB | 物体检测 |
| `resnet50_places365.pth.tar`（场景识别 ResNet50 Places365） | 约 97MB | 阶段二转 NCNN，暂空 |

## 怎么拿到这些文件

### 0) 先把代码克隆下来

```
git clone https://github.com/Topaz059/PureEdgeVLM.git
cd PureEdgeVLM
```

然后在 `app/src/main/` 下建 `assets/models/`，里面再建 `yolo`、`scene`、`ocr`、`llm` 四个文件夹。

### 1) MiniCPM5-1B 大模型（GGUF，约 657MB）

用 modelscope（国内下载快）：

```
pip install modelscope
modelscope download --model OpenBMB/MiniCPM5-1B-GGUF MiniCPM5-1B-Q4_K_M.gguf --local_dir ./models/minicpm5
```

> 注意文件名是 **`MiniCPM5-1B-Q4_K_M.gguf`**（M、B 大写），写错会 404。
> 备选：去 https://huggingface.co/openbmb/MiniCPM5-1B-GGUF 手动下载同名文件。

把下到的 `MiniCPM5-1B-Q4_K_M.gguf` 复制到 `app/src/main/assets/models/llm/`。

### 2) PP-OCRv5 NCNN（OCR，约 11MB）

```
git clone https://github.com/equationl/ncnn-android-ppocrv5.git
```

从该仓库的 `app/src/main/assets/` 里，复制这 4 个 **mobile** 开头的文件到 `app/src/main/assets/models/ocr/`：

- `PP_OCRv5_mobile_det.ncnn.param` + `PP_OCRv5_mobile_det.ncnn.bin`
- `PP_OCRv5_mobile_rec.ncnn.param` + `PP_OCRv5_mobile_rec.ncnn.bin`

> 只用 `mobile` 版（手机用，小）；别用 `server` 版（单个就 80MB+，手机跑不动也装不下）。

### 3) YOLOv11n NCNN（约 11MB）

按教程第 8.1 步用 ultralytics 导出：

```
pip install ultralytics onnx onnxsim
yolo export model=yolo11n.pt format=ncnn
```

得到 `model.ncnn.param` + `model.ncnn.bin`，复制到 `app/src/main/assets/models/yolo/`。

### 4) 场景识别（ResNet50 Places365），阶段二再做

阶段一下载 MIT 官方的 ResNet50 Places365 权重，用 `predict_places365.py` 在电脑上验证能输出 top5 场景就行，手机端用的 NCNN 版本留到阶段二转换。所以 `app/src/main/assets/models/scene/` 暂时留空，不用放东西。

## 最终的模型目录结构

```
app/src/main/assets/models/
├── yolo/   (model.ncnn.param + model.ncnn.bin)
├── scene/  (ResNet50 Places365 的 NCNN，阶段二补，暂空)
├── ocr/    (PP_OCRv5_mobile_det.ncnn.param/.bin + PP_OCRv5_mobile_rec.ncnn.param/.bin)
└── llm/    (MiniCPM5-1B-Q4_K_M.gguf)
```

放好后整个 `assets/models/` 文件夹总大小应小于 700MB。

## 更多

完整的环境搭建与模型转换步骤见仓库内的 `阶段一_环境搭建与模型转换_手把手教学.md`。
