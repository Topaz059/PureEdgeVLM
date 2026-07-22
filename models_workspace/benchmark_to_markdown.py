#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把手机端 Benchmark 产出的 CSV 转成易读的 Markdown 对比表。

用法：
    python benchmark_to_markdown.py <csv路径> [--out 输出md路径]

说明：
    CSV 由 App 里「跑 Benchmark」按钮生成，列格式为：
        model,threads,runs,avg_ms,min_ms,max_ms,fps
    其中 threads=-1 表示"自动（不扫线程）"，如 OCR（PP-OCRv5 内部用 OpenCV+OpenMP 并行，
    不响应 NCNN 的 num_threads，所以只测一次默认）。

产出：
    1) 在终端打印每个模型的延迟对比表；
    2) 同时写出一个 .md 文件（与输入同名，扩展名改 .md），方便直接贴进文档/笔记。
"""

import argparse
import csv
import os
import sys

# 模型中文名（用于表格展示，更直观）
MODEL_CN = {
    "yolo": "YOLO 目标检测",
    "scene": "ResNet50 场景识别",
    "ocr": "PP-OCRv5 文字识别",
    "llm": "MiniCPM5 本地大模型",
    "pipeline": "完整视觉流水线（检测+场景+OCR）",
}


def load_rows(csv_path):
    rows = []
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append({
                "model": r["model"].strip(),
                "threads": int(r["threads"]),
                "runs": int(r["runs"]),
                "avg_ms": float(r["avg_ms"]),
                "min_ms": float(r["min_ms"]),
                "max_ms": float(r["max_ms"]),
                "fps": float(r["fps"]),
            })
    return rows


def build_markdown(rows):
    # 按模型分组，保持出现顺序
    groups = {}
    order = []
    for r in rows:
        if r["model"] not in groups:
            groups[r["model"]] = []
            order.append(r["model"])
        groups[r["model"]].append(r)

    lines = []
    lines.append("# PureEdgeVLM 端侧推理测速对比表")
    lines.append("")
    lines.append("> 测速基线（Baseline）：骁龙 865，纯 CPU 推理（NCNN 关闭 Vulkan；大模型 llama.cpp n_threads 见各表）。后续接入 GPU / Vulkan 后的加速效果将在此基础上对比。")
    lines.append("> 数值为每次模型单独推理的延迟（毫秒），首跑为 warmup 不计入；fps = 1000 / 平均延迟。")
    lines.append("")

    for model in order:
        items = groups[model]
        cn = MODEL_CN.get(model, model)
        lines.append("## " + cn)
        lines.append("")
        # 找最优线程（平均延迟最小；OCR 只有一行自动，也照常显示）
        best = min(items, key=lambda x: x["avg_ms"])
        lines.append("| 线程数 | 运行次数 | 平均延迟(ms) | 最小(ms) | 最大(ms) | 速度(fps) |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for it in items:
            t = "自动" if it["threads"] < 0 else str(it["threads"])
            mark = " ⭐最优" if it is best else ""
            lines.append("| {} | {} | {:.1f} | {:.1f} | {:.1f} | {:.2f}{} |".format(
                t, it["runs"], it["avg_ms"], it["min_ms"], it["max_ms"], it["fps"], mark))
        best_t = "自动" if best["threads"] < 0 else str(best["threads"])
        lines.append("")
        lines.append("**结论**：{} 在 **{} 线程** 下平均延迟最低，约 {:.1f} ms（{:.2f} fps）。".format(
            cn, best_t, best["avg_ms"], best["fps"]))
        lines.append("")

    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Benchmark CSV -> Markdown 对比表")
    parser.add_argument("csv", help="Benchmark 产出的 CSV 文件路径")
    parser.add_argument("--out", help="输出 Markdown 路径（默认：与输入同名 .md）", default=None)
    args = parser.parse_args()

    if not os.path.isfile(args.csv):
        print("找不到 CSV 文件：{}".format(args.csv), file=sys.stderr)
        sys.exit(1)

    rows = load_rows(args.csv)
    if not rows:
        print("CSV 没有数据行。", file=sys.stderr)
        sys.exit(1)

    md = build_markdown(rows)
    print(md)

    out_path = args.out or (os.path.splitext(args.csv)[0] + ".md")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(md + "\n")
    print("\n已写出 Markdown：{}".format(out_path))


if __name__ == "__main__":
    main()
