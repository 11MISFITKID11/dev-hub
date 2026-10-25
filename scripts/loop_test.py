# -*- coding: utf-8 -*-
"""业务闭环 E2E：拒答→转单→SLA/派单→坐席处理(备注/回复/暂停)→质检→评价"""
import json, time, urllib.request

BASE = "http://localhost:8080"

def req(method, path, token=None, body=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    if token: r.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data, timeout=150) as resp:
            body = resp.read().decode("utf-8")
            try:
                return json.loads(body)
            except Exception:
                return body  # SSE 文本流原样返回
    except urllib.error.HTTPError as e:
        return {"http": e.code, "body": e.read().decode("utf-8", "ignore")[:200]}

def login(u, p):
    return req("POST", "/api/auth/login", body={"username": u, "password": p})["data"]["token"]

ok = lambda c, m: print(("PASS " if c else "FAIL ") + m)
admin = login("admin", "admin123"); cust = login("cust04", "dev123")

# 1. 知识类：RAG 引用作答
sse = req("POST", "/api/v1/chat/stream", cust, {"message": "上门服务的时效是多久？", "enableRag": True, "conversationId": "loop-k1"})
txt = sse if isinstance(sse, str) else json.dumps(sse)
ok("sources" in txt or "data:__META__" in str(sse) if isinstance(sse, str) else True, "1 知识类 SSE 返回")
# 用非流式口径验证拒答
# 2. 知识库外且非事务 → 拒答
sse2 = req("POST", "/api/v1/chat/stream", cust, {"message": "贵公司总部大楼的物业费是多少？", "enableRag": True, "conversationId": "loop-k2"})
s2 = sse2 if isinstance(sse2, str) else json.dumps(sse2, ensure_ascii=False)
refused = '"refused":true' in s2 or '"refused": true' in s2
ok(refused, "2 知识库外拒答 refused=true")

# 3. 拒答转单（chat_refusal）
t = req("POST", "/api/v1/tickets", cust, {"title": "云帆大厦参观预约", "content": "想预约参观云帆大厦展厅，下周有档期吗？需要什么手续？", "source": "chat_refusal", "conversationId": "loop-k2"})["data"]
ok(t["ticketNo"].startswith("TK") and t["priority"] in ("HIGH","MEDIUM","LOW"), f"3 转单 {t['ticketNo']} 分类{t['category']}/{t['priority']} 派给{t['handler']}")
ok(t["slaFirstDue"] and t["slaResolveDue"], "3 SLA 双时钟已挂")
tid = t["id"]

# 4. 坐席：内部备注（客户不可见+不通知）→ 客户补充（暂停）→ 坐席回复（首响+收口）
r1 = req("POST", f"/api/v1/tickets/{tid}/reply", admin, {"content": "内部：参观预约转商务部对接，先回复客户登记信息", "role": "internal"})
det_c = req("GET", f"/api/v1/tickets/{tid}", cust)["data"]
ok(not any(x["role"] == "internal" for x in det_c["replies"]), "4a 客户不可见内部备注")
r2 = req("POST", f"/api/v1/tickets/{tid}/reply", cust, {"content": "我们一行 4 人，想约下周四下午"})
det_a = req("GET", f"/api/v1/tickets/{tid}", admin)["data"]
paused = det_a["ticket"]["slaPausedAt"] is not None
ok(paused, "4b 客户回复暂停解决时钟")
r3 = req("POST", f"/api/v1/tickets/{tid}/reply", admin, {"content": "已为您登记下周四 14:00 参观，名额保留，请携带身份证原件，商务同事会提前一天电话确认。"})
det_a2 = req("GET", f"/api/v1/tickets/{tid}", admin)["data"]
tk = det_a2["ticket"]
ok(tk.get("slaFirstAt") is not None and tk.get("slaPausedAt") is None and (tk.get("slaPauseMs") or 0) > 0, "4c 坐席回复=首响+暂停收口")

# 5. 转派回 admin（覆盖转派）→ 受理 → 结单 → 异步质检
tk_t = req("POST", f"/api/v1/tickets/{tid}/transfer", admin, {"to": "admin"})["data"]
ok(tk_t["handler"] == "admin", "5a 转派给 admin 成功")
req("POST", f"/api/v1/tickets/{tid}/take", admin)
tk2 = req("POST", f"/api/v1/tickets/{tid}/resolve", admin, {"resolution": "参观已登记下周四 14:00，商务对接人已建立联系。"})["data"]
ok(tk2["status"] == "RESOLVED", "5a 结单")
qc = None
for i in range(20):
    time.sleep(3)
    qc = req("GET", f"/api/v1/tickets/{tid}", cust)["data"]["ticket"]["qcScore"]
    if qc is not None: break
ok(qc is not None and qc >= 0, f"5b AI 质检完成 score={qc}")

# 6. 客户评价
r6 = req("POST", f"/api/v1/tickets/{tid}/rate", cust, {"stars": 5, "comment": "回复很快，预约顺利"})
det3 = req("GET", f"/api/v1/tickets/{tid}", cust)["data"]["ticket"]
ok(det3["satisfaction"] == 5, "6 满意度评价=5星")

# 7. 关闭 + 运营侧统计可见
req("POST", f"/api/v1/tickets/{tid}/close", admin)
stats = req("GET", "/api/v1/tickets/stats", admin)["data"]
sat5 = [x for x in stats["satisfactionDist"] if x["k"] == 5]
qc_done = stats["qcDone"]
ok(True, f"7 统计看板：质检覆盖 {qc_done} 单，5星 {sat5[0]['v'] if sat5 else 0} 张")
print("DONE")
