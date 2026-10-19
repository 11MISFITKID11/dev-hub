#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对话接口压测脚本（stdlib 实现，无需第三方依赖）

用法:
  python load_test.py <并发数> <时长秒> [报告标签]
  python load_test.py 100 60 baseline

说明:
  - 打 POST /api/v1/chat（同步全链路：改写→检索→重排→生成→校验）
  - 每个请求使用唯一问题变体，避免语义缓存命中（压的是真实管道）
  - 输出：吞吐/成功率/错误分类/延迟分位数，追加写入 load_results.jsonl
"""
import io
import json
import statistics
import sys
import threading
import time
import urllib.request
import urllib.error

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

BASE = "http://localhost:8080/api/v1/chat"
BASE_QUESTIONS = [
    "遍历HashMap应该用keySet还是entrySet？",
    "线程池为什么不允许用Executors创建？",
    "SimpleDateFormat是线程安全的吗？",
    "日志打印有哪些强制规约？",
    "Arthas怎么定位慢接口？",
    "POJO属性为什么用包装类型？",
]

counter_lock = threading.Lock()
counter = [0]
results_lock = threading.Lock()
results = []
stop_at = 0


def next_question():
    with counter_lock:
        counter[0] += 1
        n = counter[0]
    base = BASE_QUESTIONS[n % len(BASE_QUESTIONS)]
    return f"{base}（压测#{n}）"


def record(ok, latency_ms, error=None):
    with results_lock:
        results.append({"ok": ok, "ms": round(latency_ms), "err": (error or "")[:80]})


def worker(idx):
    while time.time() < stop_at:
        q = next_question()
        body = json.dumps({"message": q, "enableRag": True, "mode": "DIRECT"}).encode("utf-8")
        req = urllib.request.Request(BASE, data=body,
                                     headers={"Content-Type": "application/json; charset=utf-8"})
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                payload = json.load(resp)
            code = payload.get("code")
            if code == 200:
                reply = (payload.get("data") or {}).get("reply") or ""
                is_busy = "排队超时" in reply or "人数较多" in reply
                record(not is_busy, (time.time() - t0) * 1000,
                       "busy-degraded" if is_busy else None)
            else:
                record(False, (time.time() - t0) * 1000, f"biz-{code}:{payload.get('message', '')[:60]}")
        except urllib.error.HTTPError as e:
            record(False, (time.time() - t0) * 1000, f"http-{e.code}")
        except Exception as e:
            record(False, (time.time() - t0) * 1000, type(e).__name__ + ":" + str(e)[:50])


def pct(sorted_lat, p):
    if not sorted_lat:
        return None
    idx = min(len(sorted_lat) - 1, int(len(sorted_lat) * p / 100))
    return sorted_lat[idx]


def main():
    global stop_at
    concurrency = int(sys.argv[1])
    duration = int(sys.argv[2])
    label = sys.argv[3] if len(sys.argv) > 3 else f"c{concurrency}"

    stop_at = time.time() + duration
    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(concurrency)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.time() - t0

    ok_lat = sorted(r["ms"] for r in results if r["ok"])
    fails = {}
    for r in results:
        if not r["ok"]:
            key = r["err"].split(":")[0][:40]
            fails[key] = fails.get(key, 0) + 1

    summary = {
        "label": label,
        "concurrency": concurrency,
        "duration_s": round(wall, 1),
        "total": len(results),
        "ok": len(ok_lat),
        "fail": len(results) - len(ok_lat),
        "qps": round(len(results) / wall, 2),
        "ok_qps": round(len(ok_lat) / wall, 2),
        "p50_ms": pct(ok_lat, 50),
        "p90_ms": pct(ok_lat, 90),
        "p99_ms": pct(ok_lat, 99),
        "max_ms": ok_lat[-1] if ok_lat else None,
        "errors": fails,
    }
    with open("load_results.jsonl", "a", encoding="utf-8") as f:
        f.write(json.dumps(summary, ensure_ascii=False) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
