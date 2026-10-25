# -*- coding: utf-8 -*-
"""
生成一批贴近真实分布的云帆科技客服工单（约 45 条全生命周期）。
分布骨架参考真实数据集 Endava helpdesk all_tickets.csv（48K 条，urgency/impact/类型构成）：
- urgency 高:中:低 ≈ 2:5:3；多数工单 1-2 轮回复解决；满意度 4-5 星为主、少量低分。
产出 UTF-8 SQL（SET NAMES utf8mb4），经 docker exec 导入。
用法: python seed_tickets.py > seed_tickets.sql
"""
import random
from datetime import datetime, timedelta

random.seed(42)
NOW = datetime.now()
SLA = {"HIGH": (30, 240), "MEDIUM": (120, 1440), "LOW": (240, 2880)}
CATS = ["PRODUCT", "BUG", "ACCOUNT", "BILLING", "USAGE", "OTHER"]

def dt(x): return x.strftime("'%Y-%m-%d %H:%M:%S'")

# (标题, 内容, 分类, 优先级, 距今天数, 客户, 状态, 来源, 满意度, 质检分, 坐席, 备注/回复规格)
T = []
def add(title, content, cat, pri, days, user, status, src, sat=None, qc=None, handler=None, reps=()):
    T.append(dict(title=title, content=content, cat=cat, pri=pri, days=days, user=user,
                  status=status, src=src, sat=sat, qc=qc, handler=handler, reps=reps))

# ---- 已关闭（老单，满意度+质检齐）----
add("G3 网关频繁掉线重启后恢复", "门店 3 台 G3 网关每天早高峰掉线 2-3 次，重启后恢复，影响开店自检。固件 2.3.7，Wi-Fi 上行。", "BUG", "HIGH", 28, "cust05", "CLOSED", "manual", 5, 92, "admin",
    (("agent", "已定位为早高峰 AP 负载导致 DHCP 超时，为门店更换信道并推送 2.3.8 固件，观察一周未复现。"), ("customer", "换完后确实不掉了，谢谢")))
add("云平台导出数据报错", "数据中心导出 30 天温湿度数据，点击导出后页面提示 500。专业版。", "BUG", "MEDIUM", 27, "cust03", "CLOSED", "manual", 4, 84, "admin",
    (("agent", "已修复：单次导出上限 10 万行触发校验缺陷，已按门店分段导出发您邮箱。")))
add("S100 传感器读数漂移", "冷库 4 个 S100 温度读数比标准温度计高 2-3℃，怀疑校准漂移。", "BUG", "MEDIUM", 25, "cust04", "CLOSED", "manual", 5, 90, "agent02",
    (("agent", "寄回检测确认为批次校准偏差，已整批换新并重新校准，新批次误差 ±0.5℃ 内。")))
add("发票抬头开错", "订单 CS20261008011 的专票抬头开成了旧公司名，需要重开。", "BILLING", "LOW", 24, "cust03", "CLOSED", "manual", 4, 78, "admin",
    (("agent", "已作废重开，新票顺丰寄出，运单号稍后短信发您。")))
add("忘记密码手机号已停用", "注册手机号已停用，无法收验证码重置密码。", "ACCOUNT", "MEDIUM", 23, "cust05", "CLOSED", "manual", 5, 88, "agent02",
    (("agent", "已按营业执照人工核验换绑新手机号 138****2210，请用新号登录。")))
add("网关红灯常亮无法联网", "新到的 5 台 G3 全部红灯常亮，网线直连也不行，仓库急着部署。", "BUG", "HIGH", 21, "cust04", "CLOSED", "manual", 5, 95, "admin",
    (("agent", "该批次出厂固件与新款路由器 MTU 冲突，已远程批量推送补丁，5 台全部绿灯。")))
add("专业版续费优惠咨询", "我们是 12 家门店，续费专业版有什么折扣？可以按年付吗？", "BILLING", "LOW", 20, "cust03", "CLOSED", "manual", 4, 80, "admin",
    (("agent", "10 门店以上享 85 折年付价 509 元/店，已发报价单到您邮箱，加盖公章回传即可生效。")))
add("App 提示设备已被其他账号绑定", "二手转让的门店接手后无法绑定网关，App 提示已绑定。", "ACCOUNT", "MEDIUM", 19, "cust05", "CLOSED", "manual", 4, 82, "agent02",
    (("agent", "凭购买发票已强制解绑原账号，请重新扫码绑定。")))
add("S100 电池续航明显下降", "部署 8 个月的传感器电池只剩 20%，宣传说续航 2 年。", "PRODUCT", "MEDIUM", 18, "cust04", "CLOSED", "manual", 3, 72, "admin",
    (("agent", "冷库低温环境续航会折半属正常范围，已按保修政策免费补发一批低温电池。"), ("customer", "希望能标注清楚低温续航说明")))
add("数据大屏自定义组件", "大屏想加一个门店对比的组件，现在只有单店视图。", "USAGE", "LOW", 17, "cust03", "CLOSED", "manual", 5, 86, "admin",
    (("agent", "已为您开通多店对比组件白名单，下个版本全量开放，使用说明见帮助中心。")))
add("上门巡检预约", "金卡客户年度第二次免费巡检，想约下周三上午。", "USAGE", "MEDIUM", 16, "cust03", "CLOSED", "manual", 5, 93, "admin",
    (("agent", "已约下周三 10:00，工程师张工上门，巡检报告当天发您。")))
add("工单重复扣了两次服务时长", "上月超时补偿的服务时长好像重复发放又扣回了。", "BILLING", "MEDIUM", 15, "cust04", "CLOSED", "manual", 4, 79, "agent02",
    (("agent", "确认补偿发放后系统对账误扣，已补回 30 天专业版时长并截图流水。")))
add("固件 2.4.1 升级后部分传感器掉线", "两台网关升 2.4.1 后挂的 Zigbee 传感器偶发掉线。", "BUG", "HIGH", 13, "cust05", "CLOSED", "chat_refusal", 5, 91, "admin",
    (("agent", "2.4.1 Zigbee 重连逻辑缺陷，已远程回滚 2.3.8 并登记补偿，2.4.2 修复后优先推送您。")))

# ---- 已解决（待评价或已评价）----
add("退换货发票与赠品处理", "15 天质量问题换新后旧机发票怎么处理？当时还送了两个传感器。", "BILLING", "LOW", 11, "cust03", "RESOLVED", "manual", None, 87, "agent02",
    (("agent", "换新后请以新机重新开票，赠品随旧机退回即可，不影响换新权益。")))
add("G3 Pro 断网续传数据缺失", "断网 3 天恢复后本地续传数据少了半天。", "BUG", "MEDIUM", 9, "cust04", "RESOLVED", "manual", None, 83, "admin",
    (("agent", "32GB 存储满后循环覆盖导致，已调整采样密度并补偿 7 天服务时长。")))
add("子账号权限批量调整", "20 个门店店长账号要批量开通只读本门店权限。", "ACCOUNT", "MEDIUM", 8, "cust03", "RESOLVED", "manual", 5, 89, "agent02",
    (("agent", "已按名单批量配置，每人会收到开通短信，可用手机号+验证码首登。")))
add("云平台 API 对接签名错误", "拉取数据的 API 一直返回签名错误，按文档 HMAC 步骤做的。", "USAGE", "MEDIUM", 7, "cust04", "RESOLVED", "manual", 4, 81, "admin",
    (("agent", "文档示例时间戳用了分钟级，实际要求毫秒级，已更新文档并附正确示例。")))
add("传感器配对距离不足", "网关装在配电间，隔两堵墙传感器配不上对。", "USAGE", "LOW", 6, "cust05", "RESOLVED", "manual", None, None, "agent02",
    (("agent", "金属配电间衰减大，建议网关移出或加一台做中继，已电话指导完成移机。")))
add("批量采购报价与账期", "连锁 30 家店要上 G3，走对公账期可以吗？", "PRODUCT", "MEDIUM", 5, "cust03", "RESOLVED", "manual", None, None, "admin",
    (("agent", "30 台以上可走框架协议 45 天账期，报价单与协议模板已发您邮箱。")))

# ---- 处理中（有往来）----
add("网关批量掉线疑似固件问题", "今早 12 台 G3 全部离线红灯常亮，重启无效，影响出货。", "BUG", "HIGH", 2, "dev01", "PROCESSING", "chat_refusal", None, None, "admin",
    (("customer", "设备 SN 前缀都是 CS-G3-2207。"), ("internal", "与昨日 cust05 反馈同批，疑似 2.4.1 批量缺陷，先远程收集日志。"), ("agent", "已定位为固件缺陷，正为您下发回滚补丁，预计 30 分钟内恢复，请保持通电。")))
add("退款进度查询", "上周申请的退货单 RP-88231 至今显示审核中，承诺 1 个工作日。", "BILLING", "MEDIUM", 2, "cust03", "PROCESSING", "manual", None, None, "agent02",
    (("agent", "已加急催办，仓库今日 17:00 前完成检测，退款今晚原路发起。")))
add("新店部署上门服务", "新分店下周开业，6 台网关想约上门安装。", "USAGE", "MEDIUM", 1, "cust05", "PROCESSING", "manual", None, None, "admin",
    (("agent", "已排期后日上午，工程师会提前电话确认，请备好货架位置与网线。")))
add("SSO 单点登录对接", "我们用企业微信，SSO 配置 IdP 元数据后跳转报错 SAML-021。", "ACCOUNT", "HIGH", 1, "cust03", "PROCESSING", "manual", None, None, "admin",
    (("internal", "证书链缺中间证书，让客户重新导出完整元数据。"), ("agent", "您的 IdP 证书链不完整，请重新导出包含中间证书的元数据发我。")))
add("G3 Pro 无法开机", "仓库一台 G3 Pro 通电无任何反应，电源灯不亮。", "BUG", "HIGH", 0, "dev01", "PROCESSING", "ai_agent", None, None, "admin", ())

# ---- 待受理 ----
add("云平台配额告警", "专业版提示设备数超限，但我们只有 8 台。", "USAGE", "MEDIUM", 0, "cust04", "PENDING", "manual", None, None, "admin",
    (("agent", None),))
add("对公转账未到账", "3 天前转的专业版年费至今未开通。", "BILLING", "HIGH", 0, "cust03", "PENDING", "manual", None, None, "agent02", ())
add("传感器低温失灵", "冷库 -18℃ 下 3 个 S100 直接掉线不读数。", "BUG", "MEDIUM", 0, "cust04", "PENDING", "manual", None, None, None, ())
add("App 消息推送延迟", "告警推送经常晚到半小时以上。", "BUG", "LOW", 1, "cust05", "PENDING", "chat_dislike", None, None, None, ())
add("删除测试产生的脏数据", "试用时建的测试门店想删掉。", "USAGE", "LOW", 1, "cust03", "PENDING", "manual", None, None, None, ())
add("备机服务申请", "寄修期间产线不能停，申请同型号备机。", "USAGE", "HIGH", 0, "cust04", "PENDING", "ai_agent", None, None, "admin", ())
add("重复扣费", "这个月服务费扣了两次，麻烦核实退款。", "BILLING", "HIGH", 0, "cust05", "PENDING", "manual", None, None, None, ())
add("上门巡检改期", "约的巡检想从周三改到周五。", "USAGE", "LOW", 0, "cust03", "PENDING", "manual", None, None, None, ())

out = ["SET NAMES utf8mb4;"]
base_id = 100  # 与现有自增隔离，导入后自增会越过
tid = base_id
rid = 5000
for i, t in enumerate(T):
    # 单条回复写法 (("agent","x")) 缺尾逗号时是一维元组，统一包一层
    if t["reps"] and isinstance(t["reps"][0], str):
        t["reps"] = (t["reps"],)
    tid += 1
    created = NOW - timedelta(days=t["days"], hours=random.randint(0, 8), minutes=random.randint(0, 59))
    fm, rm = SLA[t["pri"]]
    first_due = created + timedelta(minutes=fm)
    resolve_due = created + timedelta(minutes=rm)
    breached = t["status"] in ("PENDING", "PROCESSING") and t["pri"] == "HIGH" and t["days"] >= 2
    sat = t["sat"]
    qc = t["qc"]
    qc_json = f'{{"score":{qc},"problems":""}}' if qc else "NULL"
    solved_at = resolve_due - timedelta(hours=random.randint(1, 10)) if t["status"] in ("RESOLVED", "CLOSED") else None
    out.append(
        f"INSERT INTO support_ticket (id, ticket_no, username, title, content, source, category, priority, "
        f"ai_classified, status, handler, satisfaction, satisfaction_comment, qc_score, qc_result, version, deleted, "
        f"create_time, update_time, sla_first_due, sla_resolve_due, sla_first_at, sla_breach, sla_pause_ms, sla_paused_at) VALUES ("
        f"{tid}, 'TK{tid:06d}', '{t['user']}', '{t['title']}', '{t['content']}', '{t['src']}', '{t['cat']}', '{t['pri']}', "
        f"{1 if t['src'] in ('chat_refusal','chat_dislike','ai_agent') else 0}, '{t['status']}', "
        f"{('NULL' if not t['handler'] else chr(39)+t['handler']+chr(39))}, "
        f"{(sat if sat else 'NULL')}, NULL, {(qc if qc else 'NULL')}, {('NULL' if not qc else chr(39) + qc_json + chr(39))}, 0, 0, "
        f"{dt(created)}, {dt(created)}, {dt(first_due)}, {dt(resolve_due)}, "
        f"{(dt(created + timedelta(minutes=random.randint(5, fm))) if t['status'] not in ('PENDING',) else 'NULL')}, "
        f"{1 if breached else 0}, 0, NULL);")
    for role, text in t["reps"]:
        if not text: continue
        rid += 1
        author = t["user"] if role == "customer" else ("system" if role == "system" else (t["handler"] or "admin"))
        rrole = "internal" if role == "internal" else ("customer" if role == "customer" else "agent")
        at = created + timedelta(minutes=random.randint(3, 240))
        out.append(f"INSERT INTO support_ticket_reply (id, ticket_id, author, role, content, create_time) VALUES "
                   f"({rid}, {tid}, '{author}', '{rrole}', '{text}', {dt(at)});")
    if breached:
        out.append(f"INSERT INTO support_ticket_reply (id, ticket_id, author, role, content, create_time) VALUES "
                   f"(6100+{i}, {tid}, 'system', 'system', 'SLA 首响超时（首响截止 {first_due.strftime('%Y-%m-%d %H:%M')}），已通知值班坐席加急处理', {dt(first_due)});")
    if sat and sat <= 3:
        out.append(f"UPDATE support_ticket SET satisfaction_comment='希望能标注清楚低温续航说明' WHERE id={tid};")
# 标签
out.append("INSERT IGNORE INTO ticket_tag_rel (ticket_id, tag_id) VALUES (103, 1), (108, 2), (112, 1), (118, 4), (121, 3);")
print("\n".join(out))
