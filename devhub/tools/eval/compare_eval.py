#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比两次评测报告的指标差异: python compare_eval.py baseline improved"""
import io
import json
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

a_label, b_label = (sys.argv[1], sys.argv[2]) if len(sys.argv) > 2 else ("baseline", "improved")
a = json.load(open(f"eval_report_{a_label}.json", encoding="utf-8"))
b = json.load(open(f"eval_report_{b_label}.json", encoding="utf-8"))

keys = ["kwRecall@5", "MRR", "docHit@5", "top1Acc", "grounded", "correctMiss", "faithfulness", "avgLatencyMs"]
print(f"{'指标':<14} {a_label:>10}  ->  {b_label:>10}   Δ")
print("-" * 52)
for k in keys:
    va, vb = a["aggregate"].get(k), b["aggregate"].get(k)
    if va is None or vb is None:
        continue
    d = vb - va
    sign = "+" if d > 0 else ""
    print(f"{k:<14} {va:>10}  ->  {vb:>10}   {sign}{round(d, 3)}")

# 逐用例找回：baseline 漏掉的用例在 improved 中是否恢复
a_by = {c["id"]: c for c in a["cases"]}
b_by = {c["id"]: c for c in b["cases"]}
recovered, regressed = [], []
for cid, bc in b_by.items():
    ac = a_by.get(cid)
    if not ac or ac.get("kwRecall5") is None or bc.get("kwRecall5") is None:
        continue
    if bc["kwRecall5"] > ac["kwRecall5"]:
        recovered.append((cid, ac["kwRecall5"], bc["kwRecall5"], bc.get("kwMissed")))
    elif bc["kwRecall5"] < ac["kwRecall5"]:
        regressed.append((cid, ac["kwRecall5"], bc["kwRecall5"], bc.get("kwMissed")))
print(f"\n恢复用例 {len(recovered)} 个:")
for cid, x, y, missed in recovered:
    print(f"  {cid:<8} {x} -> {y}" + (f"（仍缺 {missed}）" if missed else ""))
print(f"退化用例 {len(regressed)} 个:")
for cid, x, y, missed in regressed:
    print(f"  {cid:<8} {x} -> {y}（缺 {missed}）")
