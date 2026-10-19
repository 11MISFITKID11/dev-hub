#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 黄金评测集 runner（对标 RAGAS/TruLens 的离线评测思路，轻量实现）

用法:
  python eval_runner.py baseline            # 跑分并写 eval_report_baseline.json
  python eval_runner.py improved --show fails  # 跑分并打印未达标用例

指标:
  - kwRecall@5   : 期望关键词在 top5 来源切片中的覆盖率
  - MRR          : 首个相关切片（含任一关键词）的排名倒数均值
  - docHit@5     : 期望文档出现在 top5 来源的比率
  - top1Acc      : 排名第一的来源即期望文档的比率
  - grounded     : 答案文本包含至少一个期望关键词（有据可依）
  - correctMiss  : 知识库外问题的正确拒答率（miss 类用例）
  - latency      : 端到端平均耗时

跑分前建议清缓存（保持冷启动一致）:
  docker exec agent-redis sh -c "redis-cli --scan --pattern 'agent:rag:*' | xargs -r redis-cli DEL"
"""
import io
import json
import statistics
import sys
import time
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

BASE_URL = "http://localhost:8081"
SET_PATH = "golden_set.json"
TOP_K = 5
MISS_MARKERS = ["知识库中暂无", "未检索到相关", "没有找到相关", "无法回答", "很抱歉"]


def chat(question: str, conversation_id=None):
    body = {
        "message": question,
        "enableRag": True,
        "mode": "DIRECT",
        "verifyFaithfulness": True,
    }
    if conversation_id:
        body["conversationId"] = conversation_id
    body = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL + "/api/v1/chat",
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=180) as resp:
        payload = json.load(resp)
    return payload.get("data") or {}, (time.time() - t0) * 1000


def relevant(content: str, keywords) -> bool:
    return any(kw in content for kw in keywords)


def eval_case(case: dict) -> dict:
    # 多轮用例：先发 setup 建立会话，再带 conversationId 追问（验证指代消解链路）
    conv_id = None
    if case.get("setup"):
        setup_data, _ = chat(case["setup"])
        conv_id = setup_data.get("conversationId")
    data, latency = chat(case["question"], conv_id)
    sources = data.get("sources") or []
    reply = data.get("reply") or ""
    contents = [(s.get("content") or "") for s in sources[:TOP_K]]
    filenames = [(s.get("fileName") or "") for s in sources[:TOP_K]]
    row = {
        "id": case["id"],
        "question": case["question"],
        "cached": bool(data.get("cached")),
        "latencyMs": round(latency),
        "nSources": len(sources),
        "faithfulness": data.get("faithfulness"),
    }

    if case.get("expectMiss"):
        refused = (len(sources) == 0) or any(m in reply for m in MISS_MARKERS)
        row["correctMiss"] = refused
        row["replyHead"] = reply[:60]
        return row

    kws = case.get("keywords") or []
    doc = case.get("doc") or ""
    kw_hit = [kw for kw in kws if any(kw in c for c in contents)]
    row["kwRecall5"] = round(len(kw_hit) / len(kws), 3) if kws else None
    row["kwMissed"] = [kw for kw in kws if kw not in kw_hit]
    row["docHit5"] = any(doc and doc in fn for fn in filenames)
    row["top1Doc"] = bool(filenames) and doc in filenames[0]
    mrr = 0.0
    for rank, c in enumerate(contents, start=1):
        if relevant(c, kws):
            mrr = 1.0 / rank
            break
    row["MRR"] = round(mrr, 3)
    row["grounded"] = any(kw in reply for kw in kws)
    row["topFiles"] = [fn[:18] for fn in filenames[:3]]
    return row


def aggregate(rows):
    scored = [r for r in rows if r.get("kwRecall5") is not None]
    misses = [r for r in rows if r.get("correctMiss") is not None]
    lat = [r["latencyMs"] for r in rows]
    faith_scores = [r["faithfulness"] for r in rows
                    if r.get("faithfulness") is not None and r["faithfulness"] >= 0]
    low_faith = [r["id"] for r in rows
                 if r.get("faithfulness") is not None and 0 <= r["faithfulness"] < 0.8]
    agg = {
        "cases": len(rows),
        "scored": len(scored),
        "missCases": len(misses),
        "kwRecall@5": round(statistics.mean(r["kwRecall5"] for r in scored), 3) if scored else None,
        "MRR": round(statistics.mean(r["MRR"] for r in scored), 3) if scored else None,
        "docHit@5": round(statistics.mean(1.0 if r["docHit5"] else 0.0 for r in scored), 3) if scored else None,
        "top1Acc": round(statistics.mean(1.0 if r["top1Doc"] else 0.0 for r in scored), 3) if scored else None,
        "grounded": round(statistics.mean(1.0 if r["grounded"] else 0.0 for r in scored), 3) if scored else None,
        "correctMiss": round(statistics.mean(1.0 if r["correctMiss"] else 0.0 for r in misses), 3) if misses else None,
        "faithfulness": round(statistics.mean(faith_scores), 3) if faith_scores else None,
        "lowFaithCases": low_faith,
        "avgLatencyMs": round(statistics.mean(lat)),
        "maxLatencyMs": max(lat),
        "cachedCount": sum(1 for r in rows if r.get("cached")),
    }
    return agg


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "run"
    show_fails = "--show" in sys.argv
    with open(SET_PATH, encoding="utf-8") as f:
        cases = json.load(f)["cases"]

    rows = []
    for i, case in enumerate(cases, 1):
        try:
            row = eval_case(case)
        except Exception as e:  # 单条失败不中断整个跑分
            row = {"id": case["id"], "error": str(e), "latencyMs": 0}
        rows.append(row)
        tag = "MISS" if case.get("expectMiss") else case["id"][:5]
        print(f"[{i:>2}/{len(cases)}] {case['id']:<8} {tag:<5} "
              f"kwR={row.get('kwRecall5')} MRR={row.get('MRR')} "
              f"doc@5={row.get('docHit5')} top1={row.get('top1Doc')} "
              f"gnd={row.get('grounded')} faith={row.get('faithfulness')} "
              f"miss={row.get('correctMiss')} "
              f"{row.get('latencyMs')}ms{' (cached)' if row.get('cached') else ''}", flush=True)

    agg = aggregate(rows)
    report = {"label": label, "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"), "aggregate": agg, "cases": rows}
    out = f"eval_report_{label}.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("\n===== 汇总 (%s) =====" % label)
    for k, v in agg.items():
        print(f"  {k:<16} {v}")
    print(f"\n报告已写入 {out}")

    if show_fails:
        print("\n===== 未达标用例 =====")
        for r in rows:
            problems = []
            if r.get("kwRecall5") is not None:
                if r["kwRecall5"] < 1.0:
                    problems.append(f"漏词{r.get('kwMissed')}")
                if not r["docHit5"]:
                    problems.append("文档未进top5")
                if not r["grounded"]:
                    problems.append("答案无关键词")
            if r.get("correctMiss") is False:
                problems.append("该拒答未拒答")
            if r.get("error"):
                problems.append("ERROR " + r["error"])
            if problems:
                print(f"  {r['id']:<8} {r['question'][:30]:<32} {'; '.join(problems)}")


if __name__ == "__main__":
    main()
