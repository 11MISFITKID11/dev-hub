# -*- coding: utf-8 -*-
"""DevHub 前端升级：哈希路由 + 通用 CRUD 表格引擎 + 工作台 + 文档中心 + 系统管理页
在既有单文件（登录/聊天/知识抽屉）之上追加，不动 AI 功能。"""
import io, os

P = r'D:\kaifa\javaproject\devhub\src\main\resources\static\index.html'
s = io.open(P, encoding='utf-8').read()

# ============ CSS：页面宿主/表格/弹窗/徽标 ============
css_anchor = '  #loginMask { position: fixed; inset: 0; z-index: 200;'
css_add = '''  /* ===== 宿主业务页（哈希路由） ===== */
  #pageHost { display: none; flex: 1; min-width: 0; flex-direction: column; background: var(--card); border-radius: 24px; box-shadow: var(--shadow); padding: 22px 26px; overflow-y: auto; }
  #pageHost.show { display: flex; }
  .pg-title { font-size: 19px; font-weight: 700; letter-spacing: -0.3px; margin-bottom: 4px; }
  .pg-sub { font-size: 12.5px; color: var(--text2); margin-bottom: 16px; }
  .pg-toolbar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
  .pg-toolbar input, .pg-toolbar select { padding: 8px 14px; border: 1px solid var(--input-border); border-radius: 10px; background: var(--card); color: var(--text); font-size: 13px; font-family: inherit; outline: none; }
  .pg-btn { padding: 8px 16px; border: none; border-radius: 10px; background: var(--accent); color: #fff; font-size: 13px; font-family: inherit; cursor: pointer; font-weight: 500; }
  .pg-btn:hover { background: var(--accent-h); }
  .pg-btn.ghost { background: var(--bg); color: var(--text); border: 1px solid var(--input-border); }
  .pg-btn.danger { background: rgba(255,59,48,.12); color: var(--red); }
  table.pg { width: 100%; border-collapse: collapse; font-size: 13px; }
  table.pg th { text-align: left; color: var(--text2); font-weight: 600; font-size: 12px; padding: 9px 10px; border-bottom: 1px solid var(--border); white-space: nowrap; }
  table.pg td { padding: 10px; border-bottom: 0.5px solid var(--border); vertical-align: middle; }
  table.pg tr:hover td { background: var(--thinking-bg); }
  .pg-pager { display: flex; align-items: center; gap: 8px; justify-content: flex-end; margin-top: 14px; font-size: 12.5px; color: var(--text2); }
  .badge { display: inline-block; padding: 3px 10px; border-radius: 980px; font-size: 11.5px; font-weight: 600; }
  .b-TODO { background: rgba(255,149,0,.14); color: #ff9500; } .b-DOING { background: rgba(0,113,227,.12); color: var(--accent); } .b-DONE { background: var(--green-bg); color: var(--green); }
  .b-OPEN { background: rgba(255,59,48,.12); color: var(--red); } .b-FIXED { background: rgba(0,113,227,.12); color: var(--accent); } .b-CLOSED { background: var(--green-bg); color: var(--green); }
  .b-PUBLISHED { background: var(--green-bg); color: var(--green); } .b-PENDING { background: rgba(255,149,0,.14); color: #ff9500; } .b-REJECTED { background: rgba(255,59,48,.12); color: var(--red); }
  .b-FATAL { background: rgba(255,59,48,.15); color: var(--red); } .b-SERIOUS { background: rgba(255,149,0,.14); color: #ff9500; } .b-NORMAL { background: var(--thinking-code-bg); color: var(--accent); } .b-MINOR { background: var(--bg); color: var(--text2); }
  .rowact { border: none; background: none; color: var(--accent); font-size: 12.5px; cursor: pointer; font-family: inherit; padding: 3px 6px; border-radius: 6px; }
  .rowact:hover { background: var(--thinking-code-bg); }
  .rowact.warn { color: var(--red); }
  #modalMask { position: fixed; inset: 0; z-index: 150; background: rgba(0,0,0,.35); display: none; align-items: center; justify-content: center; }
  #modalMask.show { display: flex; }
  .modal { width: min(460px, 92vw); background: var(--card); border-radius: 20px; box-shadow: var(--shadow); padding: 24px; max-height: 84vh; overflow-y: auto; }
  .modal h3 { font-size: 16px; margin-bottom: 16px; }
  .modal label { display: block; font-size: 12.5px; color: var(--text2); margin: 10px 0 5px; }
  .modal input, .modal select, .modal textarea { width: 100%; padding: 9px 12px; border: 1px solid var(--input-border); border-radius: 10px; background: var(--card); color: var(--text); font-size: 13.5px; font-family: inherit; outline: none; }
  .modal textarea { min-height: 72px; resize: vertical; }
  .modal .mbtns { display: flex; gap: 10px; margin-top: 18px; justify-content: flex-end; }
  .stat-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin-bottom: 20px; }
  .stat-card { background: var(--bg); border-radius: 16px; padding: 14px 16px; }
  .stat-card .num { font-size: 26px; font-weight: 700; letter-spacing: -0.5px; }
  .stat-card .lbl { font-size: 12px; color: var(--text2); margin-top: 2px; }
  .trend { display: flex; align-items: flex-end; gap: 8px; height: 90px; padding: 10px 4px 0; }
  .trend .bar { flex: 1; background: linear-gradient(180deg, rgba(255,59,48,.75), rgba(255,59,48,.35)); border-radius: 6px 6px 0 0; min-height: 2px; position: relative; }
  .trend .bar span { position: absolute; top: -18px; left: 50%; transform: translateX(-50%); font-size: 10.5px; color: var(--text2); }
  .trend .bar i { position: absolute; bottom: -18px; left: 50%; transform: translateX(-50%); font-style: normal; font-size: 10.5px; color: var(--text2); }

'''
assert css_anchor in s
s = s.replace(css_anchor, css_add + css_anchor, 1)

# ============ HTML：页面宿主 + 弹窗 ============
html_anchor = '<!-- 引用悬浮卡 -->'
html_add = '''<!-- 宿主业务页宿主 + 通用弹窗 -->
<div id="pageHost"></div>
<div id="modalMask"><div class="modal" id="modalBox"></div></div>

<!-- 引用悬浮卡 -->'''
assert html_anchor in s
s = s.replace(html_anchor, html_add, 1)

# pageHost 要与 chatArea 平级 —— 它插在了 .main 结束前的 drawer 之前，需移进 .main。修正：直接放到 chatArea 后面
s = s.replace('''<!-- 宿主业务页宿主 + 通用弹窗 -->
<div id="pageHost"></div>
<div id="modalMask"><div class="modal" id="modalBox"></div></div>

<!-- 引用悬浮卡 -->''', '''<!-- 引用悬浮卡 -->''')
s = s.replace('''    </footer>
  </div>
</div>

<!-- 知识库抽屉 -->''', '''    </footer>
  </div>
  <div id="pageHost"></div>
</div>

<div id="modalMask"><div class="modal" id="modalBox"></div></div>

<!-- 知识库抽屉 -->''')

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('html/css injected')
