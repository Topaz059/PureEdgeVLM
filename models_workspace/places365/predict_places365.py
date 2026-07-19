# places365 场景识别推理脚本
# 运行：py -3.10 predict_places365.py [图片路径]
# 不写路径时，默认识别 models_workspace 里的 test.jpg（一张风景图）

import sys
import torch
import torch.nn.functional as F
from torchvision import models, transforms
from PIL import Image

MODEL_PATH = r'C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365.pth.tar'
CAT_PATH   = r'C:\Users\Blue\Desktop\work\localai\models_workspace\places365\categories_places365.txt'

# 1) 读 365 个场景名字（一行一个：/a/airfield 0）
with open(CAT_PATH, encoding='utf-8') as f:
    classes = [line.strip().split(' ')[0] for line in f if line.strip()]
# 去掉名字前面的 /a/ /b/ 这类分组前缀，只留场景词（airfield）
classes = [c.split('/')[-1] if '/' in c else c for c in classes]

# 2) 搭一个 resnet50，输出 365 类（和 places365 对齐）
model = models.resnet50(num_classes=365)
ckpt = torch.load(MODEL_PATH, map_location='cpu')
sd = ckpt['state_dict']
# 权重键名带 module. 前缀，去掉它才能装进普通模型
sd = {k.replace('module.', ''): v for k, v in sd.items()}
missing, unexpected = model.load_state_dict(sd, strict=False)
if missing or unexpected:
    print('注意：有对不上的权重（不影响跑，但结果可能不准）')
    print('  缺失:', missing[:5])
    print('  多余:', unexpected[:5])
model.eval()

# 3) 图片预处理：缩到 224，转成模型要的张量并归一化
prep = transforms.Compose([
    transforms.Resize(256),
    transforms.CenterCrop(224),
    transforms.ToTensor(),
    transforms.Normalize(mean=[0.485, 0.456, 0.406],
                         std=[0.229, 0.224, 0.225]),
])

# 图片路径：命令行给了就用给的，没给用默认 test.jpg
default_img = r'C:\Users\Blue\Desktop\work\localai\models_workspace\test.jpg'
img_path = sys.argv[1] if len(sys.argv) > 1 else default_img

img = Image.open(img_path).convert('RGB')
x = prep(img).unsqueeze(0)   # 加一维"批次"（模型一次吃一批图）

# 4) 推理，取概率最高的前 5 个
with torch.no_grad():
    out = model(x)
    probs = F.softmax(out[0], dim=0)
top5 = torch.topk(probs, 5)

print(f'图片：{img_path}')
print('预测的前 5 个场景：')
for i in range(5):
    idx = top5.indices[i].item()
    score = top5.values[i].item()
    print(f'  {i+1}. {classes[idx]:<28} 可信度 {score*100:.1f}%')
