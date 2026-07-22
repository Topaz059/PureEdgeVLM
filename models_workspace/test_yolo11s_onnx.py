# 诊断脚本：直接拿 yolo11s.onnx 在标准测试图上跑，看模型本身检不检得出东西
# 用法（Windows 命令行，在 models_workspace 目录下）：
#   py -3.10 -m pip install onnxruntime
#   py -3.10 test_yolo11s_onnx.py
import numpy as np
from PIL import Image
import onnxruntime as ort

MODEL = "yolo11s.onnx"
IMG = "test.jpg"   # 这张图里通常有车/人；换成 bus.jpg 也行

CLASS_NAMES = ["person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
 "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse",
 "sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase",
 "frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard",
 "surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana",
 "apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch",
 "potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone",
 "microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear",
 "hair drier","toothbrush"]

# 读图 + letterbox 缩放到 640（和 App 里一样的预处理）
img = Image.open(IMG).convert("RGB")
w, h = img.size
scale = min(640.0 / w, 640.0 / h)
nw, nh = int(w * scale), int(h * scale)
canvas = Image.new("RGB", (640, 640), (114, 114, 114))
canvas.paste(img.resize((nw, nh)), (0, 0))
x = np.array(canvas).astype(np.float32) / 255.0   # HWC, 0~1
x = x.transpose(2, 0, 1)[None]                    # NCHW

print("图片:", IMG, "原尺寸", (w, h))
sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0].name
out = sess.run(None, {inp: x})[0]      # 形状 [1, 84, 8400]
out = out[0].transpose(1, 0)           # -> [8400, 84]
print("模型输出形状:", out.shape, "（应为 [8400, 84]）")

# 解码：前 4 个是框，后 80 个是类别分数（onnx 已带 sigmoid 前的值，这里补 sigmoid）
cls = out[:, 4:]
prob = 1.0 / (1.0 + np.exp(-cls))      # sigmoid
row_score = prob.max(axis=1)              # 每个候选框的最佳类别分数 [8400]
best_prop = int(row_score.argmax())       # 分数最高的那个框的编号
best_label = int(prob[best_prop].argmax())# 该框里分数最高的类别 (0~79)
best_score = float(row_score[best_prop])
print("raw 最大类别 logit = %.4f" % cls.max())
print("sigmoid 后最大类别分数 = %.4f  对应类别 = %s" % (best_score, CLASS_NAMES[best_label]))

# 阈值 0.25 过滤
hits = []
for i in range(out.shape[0]):
    s = prob[i]
    b = s.max()
    if b > 0.25:
        hits.append((float(b), CLASS_NAMES[int(s.argmax())]))
hits.sort(reverse=True)
print("\n=== 阈值 0.25 检出的物体（前 8 个）===")
if hits:
    for sc, name in hits[:8]:
        print("  %-12s %.3f" % (name, sc))
else:
    print("  （一个都没检出 -> 说明 yolo11s.onnx 模型/导出本身就有问题）")
