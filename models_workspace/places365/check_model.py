# 作用：确认 resnet50_places365 模型下载完整、能正常被 PyTorch 加载
# 怎么运行（在 cmd 黑窗口里，先 cd 到本文件夹，再执行下面这行）：
#   C:\Users\Blue\python\python.exe check_model.py
# 注意：一定要用上面这个"装了 torch 的 Python"，用别的会报 No module named 'torch'

import torch

# 模型文件完整路径（r 开头的字符串表示"原样路径"，反斜杠不用再转义）
model_path = r'C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365.pth.tar'

# torch.load 把硬盘上的模型文件读进内存。map_location='cpu' 表示用 CPU 加载，
# 这样就算电脑没独立显卡也不会报错。
ckpt = torch.load(model_path, map_location='cpu')

print('加载成功！')
print('顶层对象类型:', type(ckpt).__name__)   # 这个模型加载出来是个 dict（字典）

if isinstance(ckpt, dict):
    print('字典里的键:', list(ckpt.keys()))
    # 真正的模型权重（参数）藏在 'state_dict' 这个键里
    sd = ckpt['state_dict'] if 'state_dict' in ckpt else ckpt
    print('权重张量数量:', len(sd))
    # 打印前 3 个权重的"名字"和"形状"，确认内容正常、没下残
    for k in list(sd.keys())[:3]:
        print('  ', k, '->', tuple(sd[k].shape))
