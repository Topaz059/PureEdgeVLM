import torch
from torchvision import models, transforms
from PIL import Image

# 加载 MobileNetV3-Small 的 ImageNet 预训练权重（认 1000 种东西）
weights = models.MobileNet_V3_Small_Weights.IMAGENET1K_V1
model = models.mobilenet_v3_small(weights=weights)
model.eval()

# ImageNet 的 1000 个类别名字，用来把编号翻译成人话
categories = weights.meta["categories"]

# 图片预处理（手机端后面也要做一模一样的步骤）
preprocess = transforms.Compose([
    transforms.Resize(256),
    transforms.CenterCrop(224),
    transforms.ToTensor(),
    transforms.Normalize(mean=[0.485, 0.456, 0.406],
                         std=[0.229, 0.224, 0.225]),
])

# 换成你自己的图片路径
img = Image.open("test.jpg").convert("RGB")
x = preprocess(img).unsqueeze(0)

with torch.no_grad():
    out = model(x)

top5 = out[0].topk(5)
print("Top5 识别结果：")
for i in range(5):
    idx = top5.indices[i].item()
    score = top5.values[i].item()
    print(f"  {i+1}. {categories[idx]}  (可信度 {score:.3f})")
print("Scene 验证通过 ✓")
