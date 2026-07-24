#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把识别页「批量测速」产出的 CSV 转成 Markdown 对比表（可选前后对比）。

CSV 列（App 写好）：run,mode,total_ms,yolo_ms,scene_ms,ocr_ms
  - mode = serial（串行） / parallel（并行）
  - 单次导出：  python recognition_bench_to_markdown.py recognition_bench_parallel.csv
  - 前后对比：  python recognition_bench_to_markdown.py --before recognition_bench_serial.csv --after recognition_bench_parallel.csv

产出：
  1) 终端打印 Markdown 表格；
  2) 写出一个 .md 文件（单次：与输入同名 .md；对比：recognition_bench_compare.md）。
  CSV 本身可直接用 Excel 打开（即用户要的「Excel 表格」）。
"""
import argparse
import csv
import os
import statistics
import sys

MODE_CN = {
    "serial": "串行（前）",
    "parallel": "并行（后）",
}


def load_rows(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append({
                "run": int(r["run"]),
                "mode": r["mode"].strip(),
                "total": float(r["total_ms"]),
                "yolo": float(r["yolo_ms"]),
                "scene": float(r["scene_ms"]),
                "ocr": float(r["ocr_ms"]),
            })
    return rows


def _stats(vals):
    return (statistics.mean(vals), min(vals), max(vals), statistics.median(vals))


def summarize(rows, title):
    total = [r["total"] for r in rows]
    yolo = [r["yolo"] for r in rows]
    scene = [r["scene"] for r in rows]
    ocr = [r["ocr"] for r in rows]
    t = _stats(total); y = _stats(yolo); s = _stats(scene); o = _stats(ocr)
    L = []
    L.append("## " + title)
    L.append("")
    L.append("运行次数：{}".format(len(rows)))
    L.append("")
    L.append("| 指标 | 平均(ms) | 最小(ms) | 最大(ms) | 中位数(ms) |")
    L.append("| --- | --- | --- | --- | --- |")
    L.append("| 总耗时 | {:.1f} | {:.1f} | {:.1f} | {:.1f} |".format(*t))
    L.append("| YOLO 检测 | {:.1f} | {:.1f} | {:.1f} | {:.1f} |".format(*y))
    L.append("| 场景识别 | {:.1f} | {:.1f} | {:.1f} | {:.1f} |".format(*s))
    L.append("| OCR 文字 | {:.1f} | {:.1f} | {:.1f} | {:.1f} |".format(*o))
    L.append("")
    L.append("逐次原始数据：")
    L.append("")
    L.append("| 第几次 | 总耗时(ms) | YOLO(ms) | 场景(ms) | OCR(ms) |")
    L.append("| --- | --- | --- | --- | --- |")
    for r in rows:
        L.append("| {} | {:.1f} | {:.1f} | {:.1f} | {:.1f} |".format(
            r["run"], r["total"], r["yolo"], r["scene"], r["ocr"]))
    L.append("")
    return L


def compare(before, after):
    b_total = [r["total"] for r in before]; a_total = [r["total"] for r in after]
    b_yolo = [r["yolo"] for r in before]; a_yolo = [r["yolo"] for r in after]
    b_scene = [r["scene"] for r in before]; a_scene = [r["scene"] for r in after]
    b_ocr = [r["ocr"] for r in before]; a_ocr = [r["ocr"] for r in after]

    L = []
    L.append("# 识别页流水线 前后对比（串行 vs 并行）")
    L.append("")
    L.append("> 测试基线：骁龙 865，纯 CPU 推理（NCNN 关闭 Vulkan）。输入为 640×640 固定测试图；"
             "各跑 {} 次（前）/ {} 次（后）。".format(len(before), len(after)))
    L.append("> 数值为单次流水线总耗时及各模型耗时（毫秒），越低越好。")
    L.append("")

    def row(name, b, a):
        bavg = statistics.mean(b); aavg = statistics.mean(a)
        delta = aavg - bavg
        pct = (delta / bavg * 100) if bavg else 0.0
        verdict = "✅ 并行更快" if delta < 0 else ("⚠️ 并行更慢" if delta > 0 else "➖ 持平")
        return "| {} | {:.1f} | {:.1f} | {:+.1f} ({:+.1f}%) | {} |".format(
            name, bavg, aavg, delta, pct, verdict)

    L.append("## 总耗时对比")
    L.append("")
    L.append("| 指标 | 前·串行平均(ms) | 后·并行平均(ms) | 差值 | 结论 |")
    L.append("| --- | --- | --- | --- | --- |")
    L.append(row("总耗时", b_total, a_total))
    L.append(row("YOLO 检测", b_yolo, a_yolo))
    L.append(row("场景识别", b_scene, a_scene))
    L.append(row("OCR 文字", b_ocr, a_ocr))
    L.append("")
    L.append("## 前·串行 明细")
    L += summarize(before, MODE_CN.get(before[0]["mode"], "串行（前）"))
    L.append("## 后·并行 明细")
    L += summarize(after, MODE_CN.get(after[0]["mode"], "并行（后）"))
    return L


def main():
    ap = argparse.ArgumentParser(description="识别页批量测速 CSV -> Markdown 对比表")
    ap.add_argument("csv", nargs="?", help="单次 CSV 路径")
    ap.add_argument("--before", help="前（串行）CSV 路径")
    ap.add_argument("--after", help="后（并行）CSV 路径")
    ap.add_argument("--out", help="输出 Markdown 路径（默认同名 .md 或 recognition_bench_compare.md）")
    args = ap.parse_args()

    if args.before and args.after:
        b = load_rows(args.before)
        a = load_rows(args.after)
        if not b or not a:
            print("CSV 没有数据行。", file=sys.stderr); sys.exit(1)
        md = "\n".join(compare(b, a))
        out = args.out or os.path.join(os.path.dirname(os.path.abspath(args.after)), "recognition_bench_compare.md")
    elif args.csv:
        rows = load_rows(args.csv)
        if not rows:
            print("CSV 没有数据行。", file=sys.stderr); sys.exit(1)
        md = "\n".join(summarize(rows, "识别页批量测速（{}）".format(MODE_CN.get(rows[0]["mode"], rows[0]["mode"]))))
        out = args.out or (os.path.splitext(args.csv)[0] + ".md")
    else:
        print("用法：python recognition_bench_to_markdown.py <csv>   或   "
              "--before <串行csv> --after <并行csv>", file=sys.stderr)
        sys.exit(1)

    print(md)
    with open(out, "w", encoding="utf-8") as f:
        f.write(md + "\n")
    print("\n已写出 Markdown：" + out)


if __name__ == "__main__":
    main()
