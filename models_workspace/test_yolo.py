# 9.1 验证 YOLOv11n
# 用 py -3.10 test_yolo.py 运行（3.10 里装了 ultralytics）
from ultralytics import YOLO

model = YOLO("yolo11n.pt")  # 用 pt 验证即可，文件就在本目录

# 优先用官方测试图；下载失败就自动改用本地 test.jpg
try:
    result = model("https://ultralytics.com/images/bus.jpg", verbose=True)
except Exception as e:
    print("在线图片下载失败，改用本地 test.jpg：", e)
    result = model("test.jpg", verbose=True)

names = model.names  # 类别编号 -> 名称
cls_ids = result[0].boxes.cls.tolist()
print("检测到的物体：")
for c in cls_ids:
    print(f"  - {names[int(c)]}")
print("YOLO 验证通过 ✓")
