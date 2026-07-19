# 9.3 验证 OCR（用 paddleocr 官方版验证模型可用性，与手机端 NCNN 版同源）
# 需先安装：py -3.10 -m pip install paddleocr
# 运行：py -3.10 test_ocr.py
from paddleocr import PaddleOCR

ocr = PaddleOCR(use_textline_orientation=True, lang="ch")
result = ocr.ocr("test.jpg", cls=True)

print("OCR 结果：")
if result and result[0]:
    for line in result[0]:
        text, score = line[1][0], line[1][1]
        print(f"  「{text}」 (可信度 {score:.3f})")
else:
    print("  （这张图里没识别到文字，属正常——test.jpg 是风景图。只要不报错就算模型能加载）")
print("OCR 验证通过 ✓")
