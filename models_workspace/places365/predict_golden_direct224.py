# 标准答案脚本（只读验证，不碰手机）
# 用 onnxruntime 复刻手机 C++ 的喂图方式：直接缩到 224x224 + 正确 ImageNet 归一化
# 跑出来的 Top5 应该和修好的 App 在手机上打出来的 Top5 对得上
# 运行：py -3.10 predict_golden_direct224.py

import numpy as np
import onnxruntime as ort
from PIL import Image

BASE = r'C:\Users\Blue\Desktop\work\localai\models_workspace\places365'
MODEL = BASE + r'\resnet50_places365_sim.onnx'
CAT   = BASE + r'\categories_places365.txt'
IMG1  = r'C:\Users\Blue\Desktop\work\localai\models_workspace\bus.jpg'
IMG2  = r'C:\Users\Blue\Desktop\work\localai\models_workspace\test.jpg'

mean = [0.485, 0.456, 0.406]
std  = [0.229, 0.224, 0.225]

classes = [l.strip().split(' ')[0] for l in open(CAT, encoding='utf-8') if l.strip()]
classes = [c.split('/')[-1] if '/' in c else c for c in classes]

sess = ort.InferenceSession(MODEL)
iname = sess.get_inputs()[0].name
oname = sess.get_outputs()[0].name

def predict(path):
    img = Image.open(path).convert('RGB').resize((224, 224), Image.BILINEAR)  # 直接缩224，和C++一致
    x = np.array(img).astype('float32') / 255.0          # [224,224,3] RGB，范围0~1
    x = (x - np.array(mean)) / np.array(std)              # ImageNet 归一化
    x = x.transpose(2, 0, 1)[None, ...].astype(np.float32)  # [1,3,224,224]，必须 float32
    out = sess.run([oname], {iname: x})[0][0]
    e = np.exp(out - out.max())                           # softmax
    p = e / e.sum()
    idx = np.argsort(-p)[:5]
    print('图片：', path)
    for i in idx:
        print('  %-28s %.1f%%' % (classes[i], p[i] * 100))

predict(IMG1)
print()
predict(IMG2)
