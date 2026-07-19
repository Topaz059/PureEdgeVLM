# export_resnet50_places365_to_onnx.py
# 用途：把 MIT 官方 ResNet50 Places365 权重（.pth.tar）导出成 ONNX 格式
#       这是阶段二第 2 步「ResNet50 转 NCNN」的第一步（电脑上跑，用 py -3.10）
# 跑法：py -3.10 export_resnet50_places365_to_onnx.py
# 产出：同目录下的 resnet50_places365.onnx

import torch
import torchvision.models as models

# 权重文件和产出文件都在本脚本同目录
CKPT = r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365.pth.tar"
OUT  = r"C:\Users\Blue\Desktop\work\localai\models_workspace\places365\resnet50_places365.onnx"

print("正在加载官方权重（约 97MB，稍等）...")
# 1) 建一个 365 类输出的 ResNet50 空壳（Places365 是 365 类场景）
model = models.resnet50(num_classes=365)

# 2) 加载官方权重
#    resnet50_places365.pth.tar 名字带 .tar 但不是压缩包，就是 PyTorch 权重文件
#    用 torch.load 读出来是一个字典，真正权重在 'state_dict' 这个键里
#    而且键名都带 'module.' 前缀（多显卡训练套的外壳），必须去掉才能对上模型
ckpt = torch.load(CKPT, map_location="cpu")
state = ckpt.get("state_dict", ckpt)
state = {k.replace("module.", ""): v for k, v in state.items()}
model.load_state_dict(state)
model.eval()
print("权重加载成功，类别数 =", len(state))

# 3) 造一个假输入：1 张图、3 通道、224x224（ResNet50 固定输入尺寸）
dummy = torch.randn(1, 3, 224, 224)

# 4) 导出 ONNX（opset 13 兼容性最好，后面 onnx2ncnn 吃得动）
print("正在导出 ONNX...")
torch.onnx.export(
    model, dummy, OUT,
    input_names=["input"],
    output_names=["output"],
    opset_version=13,
)
print("ONNX 导出完成：", OUT)
