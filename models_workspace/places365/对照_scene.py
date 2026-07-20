# 对照脚本：把用户日志的 idx 翻成场景名，并算出标准答案（带 idx）
import numpy as np
import onnxruntime as ort
from PIL import Image

BASE = r'C:\Users\Blue\Desktop\work\localai\models_workspace\places365'
CAT   = BASE + r'\categories_places365.txt'
MODEL = BASE + r'\resnet50_places365_sim.onnx'
BUS   = r'C:\Users\Blue\Desktop\work\localai\models_workspace\bus.jpg'
TEST  = r'C:\Users\Blue\Desktop\work\localai\models_workspace\test.jpg'

mean = [0.485, 0.456, 0.406]
std  = [0.229, 0.224, 0.225]

classes = [l.strip().split(' ')[0] for l in open(CAT, encoding='utf-8') if l.strip()]
classes = [c.split('/')[-1] if '/' in c else c for c in classes]

sess = ort.InferenceSession(MODEL)
iname = sess.get_inputs()[0].name
oname = sess.get_outputs()[0].name

def top5_idx(path):
    img = Image.open(path).convert('RGB').resize((224, 224), Image.BILINEAR)
    x = np.array(img).astype('float32') / 255.0
    x = (x - np.array(mean)) / np.array(std)
    x = x.transpose(2, 0, 1)[None, ...].astype(np.float32)
    out = sess.run([oname], {iname: x})[0][0]
    e = np.exp(out - out.max())
    p = e / e.sum()
    idx = np.argsort(-p)[:5]
    return [(int(i), float(p[i])) for i in idx]

print('=== 标准答案（手机应接近这个，直接缩224+正确归一化）===')
for name, path in [('bus.jpg', BUS), ('test.jpg', TEST)]:
    print(f'[{name}]')
    for idx, score in top5_idx(path):
        print('  idx=%-4d %-28s %.1f%%' % (idx, classes[idx], score * 100))

print()
print('=== 用户日志三张图 idx -> 场景名 ===')
user = [[255, 115, 71, 321, 257],
        [30, 73, 81, 94, 306],
        [306, 140, 360, 194, 187]]
for i, shot in enumerate(user, 1):
    print(f'[用户图{i}]')
    for idx in shot:
        print('  idx=%-4d %s' % (idx, classes[idx]))
