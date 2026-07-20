# verify_resnet50_onnx.py
# 用途：在 Windows 这边用 onnxruntime 跑那个 ONNX 模型，
# 看"模型本该输出什么"，用来判断手机端(idx=306 永远第一)到底是
# "模型/转换坏了" 还是 "手机端喂图方式错"。
# 预处理严格照 MIT 官方 run_placesCNN_basic.py：
#   Resize(256,256) -> CenterCrop(224) -> ToTensor -> Normalize(均值,标准差)
import onnxruntime as ort
import numpy as np
from PIL import Image
import sys

# 图片路径从命令行传入：py -3.10 verify_resnet50_onnx.py 图片路径
if len(sys.argv) > 1:
    IMG = sys.argv[1]
else:
    IMG = r"C:\Users\Blue\Desktop\work\localai\models_workspace\bus.jpg"

ONNX = r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365_sim.onnx"
LABEL = r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\categories_places365.txt"

# 读标签（每行 "场景名 编号"，取空格前的名字）
names = []
with open(LABEL, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        names.append(line.split(" ")[0])

# MIT 官方预处理
img = Image.open(IMG).convert("RGB")
img = img.resize((256, 256), Image.BILINEAR)
l = (256 - 224) // 2
img = img.crop((l, l, l + 224, l + 224))
x = np.asarray(img, dtype=np.float32) / 255.0          # ToTensor: HWC [0,1]
x = x.transpose(2, 0, 1)                          # -> CHW
mean = np.array([0.485, 0.456, 0.406], dtype=np.float32).reshape(3, 1, 1)
std = np.array([0.229, 0.224, 0.225], dtype=np.float32).reshape(3, 1, 1)
x = (x - mean) / std
x = x[None, ...]                                  # -> NCHW (1,3,224,224)

# 跑模型
if len(sys.argv) > 2 and sys.argv[2] == "ncnn":
    # 直接读 fp16 NCNN 模型（手机端用的那份），用 ncnn Python 绑定验证
    import ncnn
    nm = ncnn.Net()
    nm.opt.use_vulkan_compute = False
    nm.load_param(r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365_opt.param")
    nm.load_model(r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365_opt.bin")
    # ncnn 输入要 HWC 的 fp32 矩阵（ch=3），且值范围按 ImageNet 归一化后
    xhwc = (x[0].transpose(1, 2, 0)).astype(np.float32)  # (224,224,3)
    mat_in = ncnn.Mat(xhwc)
    ex = nm.create_extractor()
    ex.input("input", mat_in)
    out = ncnn.Mat()
    ex.extract("output", out)
    logits = np.array(out).reshape(-1)   # (365,)
    src = "NCNN(fp16) 模型"
else:
    sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[0].name
    logits = sess.run([out_name], {in_name: x})[0][0]   # (365,)
    src = "ONNX(_sim) 模型"

# softmax -> 概率
e = np.exp(logits - logits.max())
probs = e / e.sum()

# Top5
idx = np.argsort(-probs)[:5]
print("输入图:", IMG)
print("来源:", src)
print("输出维度:", logits.shape, "（应为 (365,)）")
print("Top5:")
for i in idx:
    print(f"  idx={i}  prob={probs[i]:.4f}  name={names[i]}")
