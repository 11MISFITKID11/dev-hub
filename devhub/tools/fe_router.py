# -*- coding: utf-8 -*-
"""DevHub 前端第二段：路由 + 通用 CRUD 引擎 + 页面配置（追加 JS）"""
import io

P = r'D:\kaifa\javaproject\devhub\src\main\resources\static\index.html'
s = io.open(P, encoding='utf-8').read()

js_anchor = "/* ---------- DevHub 登录态 ---------- */"
js_add = r"""/* ================= 宿主业务页：路由 + 通用 CRUD 引擎 ================= */
let DICTS = {};

async function loadDicts() {
  try { const d = await api('/api/system/dicts'); DICTS = d || {}; } catch (e) { DICTS = {}; }
}

function esc(x) { return String(x == null ? '' : x).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function badge(v) { return v ? `<span class="badge b-${esc(v)}">${esc(dictLabel(v) || v)}</span>` : '-'; }
function dictLabel(v) {
  for (const k in DICTS) { const hit = DICTS[k].find(d => d.value === String(v)); if (hit) return hit.label; }
  return null;
}

// ---------- 路由 ----------
function navTo(path) { location.hash = path; }
window.addEventListener('hashchange', route);
function route() {
  const path = location.hash.replace('#', '') || '/ai/chat';
  const chat = path.startsWith('/ai');
  $('chatArea').style.display = chat ? 'flex' : 'none';
  $('pageHost').classList.toggle('show', !chat);
  document.querySelectorAll('#dhNav .nv').forEach(el => {
    el.classList.toggle('active', el.dataset.path === (chat ? '/ai' : path));
  });
  if (!chat) renderPage(path);
}

// ---------- 通用弹窗 ----------
function openModal(title, fieldsHtml, onSubmit) {
  const box = $('modalBox');
  box.innerHTML = `<h3>${esc(title)}</h3><form id="modalForm" onsubmit="return false">${fieldsHtml}
    <div class="mbtns"><button class="pg-btn ghost" onclick="closeModal()">取消</button>
    <button class="pg-btn" id="modalOk">确定</button></div></form>`;
  $('modalMask').classList.add('show');
  $('modalOk').onclick = async () => {
    const data = {};
    box.querySelectorAll('[name]').forEach(el => { data[el.name] = el.value; });
    const ok = await onSubmit(data);
    if (ok !== false) closeModal();
  };
}
function closeModal() { $('modalMask').classList.remove('show'); }
$('modalMask')?.addEventListener('click', e => { if (e.target.id === 'modalMask') closeModal(); });

function fieldHtml(f, val) {
  val = val == null ? '' : val;
  if (f.type === 'select') {
    const opts = (typeof f.options === 'string' ? (DICTS[f.options] || []) : f.options)
      .map(o => `<option value="${esc(o.value)}" ${String(val) === String(o.value) ? 'selected' : ''}>${esc(o.label)}</option>`).join('');
    return `<label>${esc(f.label)}</label><select name="${f.key}">${opts}</select>`;
  }
  if (f.type === 'textarea') return `<label>${esc(f.label)}</label><textarea name="${f.key}">${esc(val)}</textarea>`;
  if (f.type === 'date') return `<label>${esc(f.label)}</label><input type="date" name="${f.key}" value="${esc(val)}">`;
  return `<label>${esc(f.label)}</label><input name="${f.key}" value="${esc(val)}" placeholder="${esc(f.ph || '')}">`;
}

// ---------- 通用 CRUD 页 ----------
function crudPage(cfg) {
  const state = { page: 1, size: 10, filters: {} };
  return async function render(host) {
    host.innerHTML = `<div class="pg-title">${esc(cfg.title)}</div><div class="pg-sub">${esc(cfg.sub || '')}</div><div class="pg-toolbar" id="pgTb"></div><div id="pgBody">加载中…</div><div class="pg-pager" id="pgPager"></div>`;
    const tb = $('pgTb');
    // 过滤器 + 新建按钮
    let th = '';
    (cfg.filters || []).forEach(f => {
      if (f.type === 'select') {
        const opts = (typeof f.options === 'string' ? (DICTS[f.options] || []) : f.options)
          .map(o => `<option value="${esc(o.value)}">${esc(o.label)}</option>`).join('');
        th += `<select id="f_${f.key}"><option value="">${esc(f.label)}：全部</option>${opts}</select>`;
      } else {
        th += `<input id="f_${f.key}" placeholder="${esc(f.ph || f.label)}">`;
      }
    });
    th += `<button class="pg-btn" id="pgNew">${esc(cfg.newLabel || '新建')}</button>`;
    tb.innerHTML = th;
    (cfg.filters || []).forEach(f => {
      const el = $('f_' + f.key);
      el.addEventListener(el.tagName === 'SELECT' ? 'change' : 'keydown', ev => {
        if (ev.type === 'keydown' && ev.key !== 'Enter') return;
        state.filters[f.key] = el.value; state.page = 1; loadBody();
      });
    });
    $('pgNew').onclick = () => openForm(null);
    if (cfg.extra) await cfg.extra(host);

    async function loadBody() {
      const qs = new URLSearchParams({ page: state.page, size: state.size });
      Object.entries(state.filters).forEach(([k, v]) => { if (v) qs.set(k, v); });
      let d;
      try { d = await api(`${cfg.api}?${qs}`); } catch (e) { $('pgBody').innerHTML = `<div style="color:var(--red)">${esc(e.message)}</div>`; return; }
      const rows = d.records || [];
      if (!rows.length) { $('pgBody').innerHTML = '<div style="color:var(--text2);padding:20px;text-align:center">暂无数据</div>'; }
      else {
        let h = '<table class="pg"><tr>' + cfg.columns.map(c => `<th>${esc(c.title)}</th>`).join('') + '<th style="width:140px">操作</th></tr>';
        rows.forEach(r => {
          h += '<tr>' + cfg.columns.map(c => `<td>${c.render ? c.render(r) : esc(r[c.key])}</td>`).join('');
          const acts = (cfg.rowActions ? cfg.rowActions(r) : ['edit', 'del']).map(a => {
            if (a === 'edit') return `<button class="rowact" onclick="PG.edit(${r.id})">编辑</button>`;
            if (a === 'del') return `<button class="rowact warn" onclick="PG.del(${r.id})">删除</button>`;
            return a;
          }).join('');
          h += `<td>${acts}</td></tr>`;
        });
        $('pgBody').innerHTML = h + '</table>';
      }
      const pages = Math.max(1, Math.ceil((d.total || 0) / state.size));
      $('pgPager').innerHTML = `<button class="pg-btn ghost" ${state.page <= 1 ? 'disabled' : ''} onclick="PG.go(${state.page - 1})">上一页</button>
        <span>${state.page} / ${pages}（共 ${d.total || 0} 条）</span>
        <button class="pg-btn ghost" ${state.page >= pages ? 'disabled' : ''} onclick="PG.go(${state.page + 1})">下一页</button>`;
    }

    window.PG = {
      go: p => { state.page = p; loadBody(); },
      edit: id => { const row = lastRows.find(r => r.id === id); if (row) openForm(row); },
      del: async id => { if (!confirm('确认删除？')) return; try { await api(cfg.api + '/' + id, { method: 'DELETE' }); loadBody(); } catch (e) { alert(e.message); } }
    };
    let lastRows = [];
    const _load = loadBody;
    loadBody = async function () { await _load(); lastRows = ((await api(buildQs())) || {records:[]}).records || lastRows; };
    function buildQs() { return cfg.api; } // 简化：edit 用最近一次表格数据
    // 更稳做法：渲染时缓存 rows
    const origLoad = loadBody;
    loadBody = async function () {
      const qs = new URLSearchParams({ page: state.page, size: state.size });
      Object.entries(state.filters).forEach(([k, v]) => { if (v) qs.set(k, v); });
      let d; try { d = await api(`${cfg.api}?${qs}`); } catch (e) { $('pgBody').innerHTML = `<div style="color:var(--red)">${esc(e.message)}</div>`; return; }
      lastRows = d.records || [];
      // 复用原渲染
      if (!lastRows.length) { $('pgBody').innerHTML = '<div style="color:var(--text2);padding:20px;text-align:center">暂无数据</div>'; }
      else {
        let h = '<table class="pg"><tr>' + cfg.columns.map(c => `<th>${esc(c.title)}</th>`).join('') + '<th style="width:150px">操作</th></tr>';
        lastRows.forEach(r => {
          h += '<tr>' + cfg.columns.map(c => `<td>${c.render ? c.render(r) : esc(r[c.key])}</td>`).join('');
          const acts = (cfg.rowActions ? cfg.rowActions(r) : ['edit', 'del']).map(a => {
            if (a === 'edit') return `<button class="rowact" onclick="PG.edit(${r.id})">编辑</button>`;
            if (a === 'del') return `<button class="rowact warn" onclick="PG.del(${r.id})">删除</button>`;
            return a;
          }).join('');
          h += `<td>${acts}</td></tr>`;
        });
        $('pgBody').innerHTML = h + '</table>';
      }
      const pages = Math.max(1, Math.ceil((d.total || 0) / state.size));
      $('pgPager').innerHTML = `<button class="pg-btn ghost" ${state.page <= 1 ? 'disabled' : ''} onclick="PG.go(${state.page - 1})">上一页</button>
        <span>${state.page} / ${pages}（共 ${d.total || 0} 条）</span>
        <button class="pg-btn ghost" ${state.page >= pages ? 'disabled' : ''} onclick="PG.go(${state.page + 1})">下一页</button>`;
    };

    function openForm(row) {
      const fields = cfg.form.map(f => fieldHtml(f, row ? row[f.key] : f.def)).join('');
      openModal((row ? '编辑' : '新建') + cfg.title.slice(0, 2), fields, async data => {
        try {
          if (row) await api(cfg.api + '/' + row.id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
          else await api(cfg.api, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
          loadBody(); return true;
        } catch (e) { alert(e.message); return false; }
      });
    }
    loadBody();
  };
}

// ---------- 页面配置 ----------
let PROJECT_OPTS = [], USER_OPTS = [];
async function loadOptions() {
  try { PROJECT_OPTS = (await api('/api/projects/options')).map(p => ({ value: p.id, label: p.name })); } catch (e) {}
  try { USER_OPTS = (await api('/api/system/users/options')).map(u => ({ value: u.username, label: u.nickname || u.username })); } catch (e) {}
}
function projName(id) { const p = PROJECT_OPTS.find(x => String(x.value) === String(id)); return p ? p.label : id; }
function userName(v) { const u = USER_OPTS.find(x => x.value === v); return u ? u.label : (v || '-'); }

const PAGES = {
  '/dashboard': { title: '工作台', render: renderDashboard },
  '/project': { title: '项目管理', crud: { api: '/api/projects', title: '项目', sub: '研发项目全生命周期管理',
      filters: [{ key: 'keyword', ph: '项目名' }],
      columns: [
        { key: 'name', title: '项目' }, { key: 'code', title: '编码' },
        { key: 'ownerUsername', title: '负责人', render: r => esc(userName(r.ownerUsername)) },
        { key: 'status', title: '状态', render: r => badge('0' === String(r.status) ? 'TODO' : 'DONE') },
        { key: 'startDate', title: '起止', render: r => esc((r.startDate || '') + ' ~ ' + (r.endDate || '')) } ],
      form: [ { key: 'name', label: '项目名' }, { key: 'code', label: '编码' },
        { key: 'ownerUsername', label: '负责人', type: 'select', options: USER_OPTS },
        { key: 'status', label: '状态', type: 'select', options: 'project_status' },
        { key: 'startDate', label: '开始', type: 'date' }, { key: 'endDate', label: '结束', type: 'date' },
        { key: 'description', label: '描述', type: 'textarea' } ] } },
  '/task': { title: '任务中心', crud: { api: '/api/tasks', title: '任务', sub: '状态流转：待办 → 进行中 → 已完成',
      filters: [{ key: 'status', label: '状态', type: 'select', options: 'task_status' },
                { key: 'projectId', label: '项目', type: 'select', options: [] }, { key: 'keyword', ph: '标题' }],
      columns: [
        { key: 'title', title: '标题' },
        { key: 'projectId', title: '项目', render: r => esc(projName(r.projectId)) },
        { key: 'assigneeUsername', title: '指派给', render: r => esc(userName(r.assigneeUsername)) },
        { key: 'priority', title: '优先级', render: r => badge({L:'MINOR',M:'NORMAL',H:'SERIOUS',U:'FATAL'}[r.priority] || 'NORMAL') },
        { key: 'status', title: '状态', render: r => badge(r.status) },
        { key: 'dueDate', title: '截止' } ],
      rowActions: r => r.status !== 'DONE'
        ? ['edit', `<button class="rowact" onclick="PGADV(${r.id},true)">推进</button>`,
           `<button class="rowact warn" onclick="PGADV(${r.id},false)">回退</button>`, 'del']
        : ['edit', `<button class="rowact warn" onclick="PGADV(${r.id},false)">回退</button>`, 'del'],
      form: [ { key: 'title', label: '标题' },
        { key: 'projectId', label: '项目', type: 'select', options: [] },
        { key: 'assigneeUsername', label: '指派给', type: 'select', options: [] },
        { key: 'priority', label: '优先级', type: 'select', options: 'task_priority' },
        { key: 'status', label: '状态', type: 'select', options: 'task_status' },
        { key: 'dueDate', label: '截止日', type: 'date' },
        { key: 'content', label: '内容', type: 'textarea' } ] } },
  '/defect': { title: '缺陷跟踪', crud: { api: '/api/defects', title: '缺陷', sub: '缺陷全流程：提交 → 修复 → 关闭',
      filters: [{ key: 'status', label: '状态', type: 'select', options: 'defect_status' },
                { key: 'severity', label: '严重级', type: 'select', options: 'defect_severity' }],
      columns: [
        { key: 'title', title: '标题' },
        { key: 'projectId', title: '项目', render: r => esc(projName(r.projectId)) },
        { key: 'severity', title: '严重级', render: r => badge(r.severity) },
        { key: 'status', title: '状态', render: r => badge(r.status) },
        { key: 'assigneeUsername', title: '修复人', render: r => esc(userName(r.assigneeUsername)) } ],
      form: [ { key: 'title', label: '标题' },
        { key: 'projectId', label: '项目', type: 'select', options: [] },
        { key: 'severity', label: '严重级', type: 'select', options: 'defect_severity' },
        { key: 'status', label: '状态', type: 'select', options: 'defect_status' },
        { key: 'assigneeUsername', label: '修复人', type: 'select', options: [] },
        { key: 'description', label: '描述', type: 'textarea' } ] } },
  '/doc': { title: '文档中心', render: renderDocCenter },
  '/report': { title: '报表统计', render: renderDashboard },
  '/system': { title: '系统管理', render: renderSystem },
};

window.PGADV = async (id, forward) => {
  try { await api(`/api/tasks/${id}/advance?forward=${forward}`, { method: 'POST' }); route(); }
  catch (e) { alert(e.message); }
};

// ---------- 工作台 ----------
async function renderDashboard(host) {
  host.innerHTML = '<div class="pg-title">工作台</div><div class="pg-sub">研发概览与 AI 模块运行状态</div><div id="dbBody">加载中…</div>';
  let d; try { d = await api('/api/report/dashboard'); } catch (e) { $('dbBody').innerHTML = `<div style="color:var(--red)">${esc(e.message)}</div>`; return; }
  const cards = [
    ['活跃项目', d.activeProjects], ['我的待办', d.myTodo], ['任务完成率', d.completionRate + '%'],
    ['待办/进行/完成', `${d.todoTasks}/${d.doingTasks}/${d.doneTasks}`], ['未修复缺陷', d.openDefects],
    ['知识库文档', d.aiOverview.documents], ['AI 会话', d.aiOverview.conversations], ['知识缺口', d.aiOverview.gaps]];
  const maxT = Math.max(...d.defectTrend7d.map(x => x.count), 1);
  const bars = d.defectTrend7d.map(x => `<div class="bar" style="height:${Math.max(4, x.count / maxT * 70)}px"><span>${x.count}</span><i>${x.date}</i></div>`).join('');
  $('dbBody').innerHTML = `
    <div class="stat-cards">${cards.map(c => `<div class="stat-card"><div class="num">${esc(c[1])}</div><div class="lbl">${esc(c[0])}</div></div>`).join('')}</div>
    <div class="pg-title" style="font-size:15px">近 7 日缺陷趋势</div>
    <div class="trend" style="margin:26px 0 24px">${bars}</div>`;
}

// ---------- 文档中心 ----------
async function renderDocCenter(host) {
  host.innerHTML = `<div class="pg-title">文档中心</div><div class="pg-sub">分类管理 · 解析状态 · 审核发布 —— 发布后的文档自动进入 AI 知识库（Tika 解析 → 切片 → 向量化）</div>
    <div class="pg-toolbar"><select id="dcCat"></select><select id="dcAudit"><option value="">审核：全部</option><option value="PENDING">待审核</option><option value="PUBLISHED">已发布</option><option value="REJECTED">已驳回</option></select>
    <input type="file" id="dcFile" hidden><button class="pg-btn" id="dcUpload">上传文档</button><button class="pg-btn ghost" id="dcNewCat">新建分类</button></div>
    <div id="dcBody"></div><div class="pg-pager" id="dcPager"></div>`;
  let cats = []; try { cats = await api('/api/doc/categories'); } catch (e) {}
  $('dcCat').innerHTML = '<option value="">分类：全部</option>' + cats.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
  $('dcNewCat').onclick = () => openModal('新建分类', fieldHtml({ key: 'name', label: '分类名' }) + fieldHtml({ key: 'sort', label: '排序', def: '0' }), async data => {
    try { await api('/api/doc/categories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); renderPage('/doc'); return true; } catch (e) { alert(e.message); return false; }
  });
  $('dcUpload').onclick = () => $('dcFile').click();
  $('dcFile').onchange = async e => {
    const f = e.target.files[0]; if (!f) return;
    const fd = new FormData(); fd.append('file', f);
    if ($('dcCat').value) fd.append('categoryId', $('dcCat').value);
    $('dcUpload').textContent = '解析入库中…'; $('dcUpload').disabled = true;
    try { const r = await api('/api/v1/documents/upload', { method: 'POST', body: fd }); alert(`已入库：${r.chunkCount} 个切片，待审核发布`); loadDocs(); }
    catch (err) { alert('上传失败：' + err.message); }
    $('dcUpload').textContent = '上传文档'; $('dcUpload').disabled = false; e.target.value = '';
  };
  const st = { page: 1 };
  ['dcCat', 'dcAudit'].forEach(id => $(id).addEventListener('change', () => { st.page = 1; loadDocs(); }));
  window.DCAUDIT = async (id, pass) => {
    try { await api(`/api/doc/documents/${id}/audit?pass=${pass}`, { method: 'POST' }); loadDocs(); } catch (e) { alert(e.message); }
  };
  async function loadDocs() {
    const qs = new URLSearchParams({ page: st.page, size: 10 });
    if ($('dcCat').value) qs.set('categoryId', $('dcCat').value);
    if ($('dcAudit').value) qs.set('auditStatus', $('dcAudit').value);
    let d; try { d = await api('/api/doc/documents?' + qs); } catch (e) { $('dcBody').innerHTML = `<div style="color:var(--red)">${esc(e.message)}</div>`; return; }
    $('dcBody').innerHTML = `<table class="pg"><tr><th>文件</th><th>分类</th><th>解析状态</th><th>切片数</th><th>上传人</th><th>审核</th><th>操作</th></tr>` +
      (d.records || []).map(r => `<tr>
        <td>${esc(r.fileName)}</td>
        <td>${esc((cats.find(c => c.id === r.categoryId) || {}).name || '-')}</td>
        <td>${badge(r.lifeStatus === 'active' ? 'DONE' : 'PENDING')}</td>
        <td>${r.chunkCount}</td>
        <td>${esc(r.uploader || '-')}</td>
        <td>${badge(r.auditStatus)}</td>
        <td>${r.auditStatus === 'PENDING' ? `<button class="rowact" onclick="DCAUDIT(${r.id ? "'" + r.id + "'" : "''"},true)">发布</button><button class="rowact warn" onclick="DCAUDIT('${r.id}',false)">驳回</button>` : '-'}</td>
      </tr>`).join('') + '</table>';
    const pages = Math.max(1, Math.ceil((d.total || 0) / 10));
    $('dcPager').innerHTML = `<button class="pg-btn ghost" ${st.page <= 1 ? 'disabled' : ''} onclick="DCPG(${st.page - 1})">上一页</button><span>${st.page} / ${pages}</span><button class="pg-btn ghost" ${st.page >= pages ? 'disabled' : ''} onclick="DCPG(${st.page + 1})">下一页</button>`;
  }
  window.DCPG = p => { st.page = p; loadDocs(); };
  loadDocs();
}

// ---------- 系统管理 ----------
async function renderSystem(host) {
  host.innerHTML = `<div class="pg-title">系统管理</div><div class="pg-sub">用户 · 部门树 · 字典 · 操作日志 · 登录日志 · 改密</div>
    <div class="pg-toolbar"><button class="pg-btn" id="syNewUser">新建用户</button>
    <input id="syKw" placeholder="用户名搜索"><button class="pg-btn ghost" id="syPwd">修改我的密码</button></div>
    <div id="syUsers"></div>
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:22px">
      <div><div class="pg-title" style="font-size:15px">部门树</div><div id="syDept"></div></div>
      <div><div class="pg-title" style="font-size:15px">字典</div><div id="syDict"></div></div>
    </div>
    <div class="pg-title" style="font-size:15px;margin-top:22px">最近操作日志</div><div id="syLog"></div>
    <div class="pg-title" style="font-size:15px;margin-top:22px">最近登录日志</div><div id="syLoginLog"></div>`;
  const isAdmin = (AUTH.user && (AUTH.user.menus || []).some(m => m.id === 11));
  if (isAdmin) $('syNewUser').style.display = '';
  else { $('syNewUser').style.display = 'none'; $('syKw').style.display = 'none'; }

  $('syPwd').onclick = () => openModal('修改密码',
    fieldHtml({ key: 'oldPassword', label: '原密码' }) + fieldHtml({ key: 'newPassword', label: '新密码（≥6位）' }),
    async data => { try { await api('/api/system/profile/password', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); doLogout(); return true; } catch (e) { alert(e.message); return false; } });

  // 用户表（管理员可见；普通用户只读）
  async function loadUsers() {
    let d; try { d = await api('/api/system/users?size=50' + ($('syKw').value ? '&keyword=' + encodeURIComponent($('syKw').value) : '')); } catch (e) { $('syUsers').innerHTML = ''; return; }
    $('syUsers').innerHTML = `<table class="pg"><tr><th>用户名</th><th>昵称</th><th>部门</th><th>角色</th><th>状态</th>${isAdmin ? '<th>操作</th>' : ''}</tr>` +
      (d.records || []).map(u => `<tr><td>${esc(u.username)}</td><td>${esc(u.nickname || '-')}</td><td>${esc(u.dept_name || '-')}</td>
        <td>${esc(u.roles || '-')}</td><td>${u.status === '0' ? badge('DONE') : badge('REJECTED')}</td>
        ${isAdmin ? `<td><button class="rowact" onclick="SYRP('${esc(u.username)}')">重置密码</button><button class="rowact warn" onclick="SYDEL('${esc(u.username)}')">删除</button></td>` : ''}</tr>`).join('') + '</table>';
  }
  $('syKw').addEventListener('keydown', e => { if (e.key === 'Enter') loadUsers(); });
  window.SYRP = u => openModal('重置密码：' + u, fieldHtml({ key: 'password', label: '新密码（≥6位）' }), async data => {
    try { await api(`/api/system/users/${u}/resetPwd`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); return true; } catch (e) { alert(e.message); return false; } });
  window.SYDEL = async u => { if (!confirm(`删除用户 ${u}？`)) return; try { await api(`/api/system/users/${u}`, { method: 'DELETE' }); loadUsers(); } catch (e) { alert(e.message); } };
  $('syNewUser').onclick = () => {
    let deptOpts = [];
    openModal('新建用户',
      fieldHtml({ key: 'username', label: '用户名' }) + fieldHtml({ key: 'password', label: '初始密码' }) +
      fieldHtml({ key: 'nickname', label: '昵称' }) +
      `<label>角色</label><select name="roleKey"><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select>`,
      async data => {
        try { await api('/api/system/users', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); loadUsers(); return true; }
        catch (e) { alert(e.message); return false; } });
  };

  // 部门树
  try {
    const tree = await api('/api/system/depts');
    const flat = (ns, d) => ns.map(n => `<div style="padding-left:${d * 18}px;font-size:13px;padding:4px 0">${d > 0 ? '└ ' : ''}${esc(n.name)}</div>` + flat(n.children || [], d + 1)).join('');
    $('syDept').innerHTML = flat(tree, 0);
  } catch (e) { $('syDept').innerHTML = '<div style="color:var(--text2);font-size:12px">无权限</div>'; }

  // 字典
  $('syDict').innerHTML = Object.entries(DICTS).map(([k, vs]) =>
    `<div style="font-size:12px;margin:6px 0"><b>${esc(k)}</b>：${vs.map(v => esc(v.label)).join(' / ')}</div>`).join('');

  // 日志
  try {
    const lg = await api('/api/system/logs/oper?size=8');
    $('syLog').innerHTML = `<table class="pg"><tr><th>模块</th><th>动作</th><th>操作人</th><th>耗时</th><th>状态</th></tr>` +
      (lg.records || []).map(l => `<tr><td>${esc(l.module)}</td><td>${esc(l.action)}</td><td>${esc(l.username || '-')}</td><td>${l.costMs}ms</td><td>${l.status === 1 ? badge('DONE') : badge('OPEN')}</td></tr>`).join('') + '</table>';
  } catch (e) { $('syLog').innerHTML = '<div style="color:var(--text2);font-size:12px">无权限</div>'; }
  try {
    const ll = await api('/api/system/logs/login?size=8');
    $('syLoginLog').innerHTML = `<table class="pg"><tr><th>用户</th><th>IP</th><th>结果</th><th>时间</th></tr>` +
      (ll.records || []).map(l => `<tr><td>${esc(l.username)}</td><td>${esc(l.ip)}</td><td>${l.status === 1 ? badge('DONE') : badge('OPEN')}</td><td>${esc((l.createTime || '').replace('T', ' ').slice(5, 16))}</td></tr>`).join('') + '</table>';
  } catch (e) { $('syLoginLog').innerHTML = '<div style="color:var(--text2);font-size:12px">无权限</div>'; }

  loadUsers();
}

// ---------- 页面分发 ----------
async function renderPage(path) {
  const cfg = PAGES[path];
  const host = $('pageHost');
  if (!cfg) { host.innerHTML = `<div class="pg-title">${esc(path)}</div><div class="pg-sub">页面未实现（骨架占位）</div>`; return; }
  await loadOptions();
  if (cfg.render) return cfg.render(host);
  const c = { ...cfg.crud };
  // 动态下拉注入（项目/人员）
  (c.filters || []).forEach(f => { if (f.type === 'select' && Array.isArray(f.options) && !f.options.length) f.options = f.key === 'projectId' ? PROJECT_OPTS : USER_OPTS; });
  (c.form || []).forEach(f => { if (f.type === 'select' && Array.isArray(f.options) && !f.options.length) f.options = f.key === 'projectId' ? PROJECT_OPTS : USER_OPTS; });
  return crudPage(c)(host);
}

/* ---------- DevHub 登录态 ---------- */"""

assert js_anchor in s
s = s.replace(js_anchor, js_add, 1)

# 2) 菜单点击接路由 + 登录后进入路由
old_nav = """  const html = top.map(m => {
    const isAi = m.id === 6;
    return `<span class="nv ${isAi ? 'active' : 'dim'}" title="${isAi ? '' : '骨架占位——传统宿主模块待实现'}">${m.name}</span>`;
  }).join('') + aiKids.map(k =>
    `<span class="nv dim" title="AI 子页面（骨架）">· ${k.name}</span>`).join('');"""
new_nav = """  const html = top.map(m => {
    const isAi = m.id === 6;
    return `<span class="nv ${isAi ? 'active' : 'dim'}" data-path="${m.path || ''}" title="${isAi ? '智能问答' : '宿主业务页'}" onclick="navTo('${isAi ? '/ai/chat' : (m.path || '/dashboard')}')">${m.name}</span>`;
  }).join('') + aiKids.map(k =>
    `<span class="nv dim" onclick="navTo('/ai/chat')">· ${k.name}</span>`).join('');"""
assert old_nav in s
s = s.replace(old_nav, new_nav)

old_enter = """function enterApp() {
  $('loginMask').classList.add('hidden');
  renderDhNav(); renderDhUser();"""
new_enter = """async function enterApp() {
  $('loginMask').classList.add('hidden');
  renderDhNav(); renderDhUser();
  await loadDicts();"""
assert old_enter in s
s = s.replace(old_enter, new_enter)

old_init = """if (AUTH.token) { enterApp(); } else { $('loginMask').classList.remove('hidden'); }"""
new_init = """if (AUTH.token) { enterApp().then(() => route()); } else { $('loginMask').classList.remove('hidden'); }"""
assert old_init in s
s = s.replace(old_init, new_init)

io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print('router + CRUD engine injected, total lines:', s.count(chr(10)))
