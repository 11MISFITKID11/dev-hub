# -*- coding: utf-8 -*-
import io
p = r'src\main\resources\static\index.html'
s = io.open(p, encoding='utf-8').read()

# 1) CSS：抽屉动画 + 浮动拉出按钮
old = "  #dhNav { display: flex; flex-wrap: wrap; gap: 4px; padding: 10px 12px;"
new = """  aside { transition: margin-left .3s cubic-bezier(.4,0,.2,1); }
  aside.tucked { margin-left: -308px; }
  #sideToggle { width: 34px; height: 34px; border-radius: 10px; border: 1px solid var(--input-border); background: var(--card); color: var(--text); cursor: pointer; font-size: 15px; display: flex; align-items: center; justify-content: center; }
  #sideToggle:hover { border-color: var(--accent); color: var(--accent); }
  #sidePull { position: fixed; left: 10px; top: 76px; z-index: 60; width: 38px; height: 38px; border-radius: 12px; border: none; background: var(--accent); color: #fff; font-size: 16px; cursor: pointer; box-shadow: 0 4px 14px rgba(0,113,227,.35); display: none; }
  #sidePull.show { display: flex; align-items: center; justify-content: center; }
  #dhNav { display: flex; flex-direction: column; gap: 2px; padding: 8px; }
  #dhNav .nvgroup > .nvt { display: flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 600; padding: 8px 10px; border-radius: 10px; cursor: pointer; color: var(--text2); }
  #dhNav .nvgroup > .nvt:hover { background: var(--bg); color: var(--text); }
  #dhNav .nvgroup > .nvt .chev { margin-left: auto; transition: transform .2s; font-size: 9px; }
  #dhNav .nvgroup.open > .nvt .chev { transform: rotate(90deg); }
  #dhNav .nvgroup.open > .nvt { color: var(--accent); }
  #dhNav .nvkids { display: none; flex-direction: column; gap: 1px; padding-left: 16px; }
  #dhNav .nvgroup.open > .nvkids { display: flex; }
  #dhNav .nv { font-size: 12px; padding: 6px 10px; border-radius: 8px; color: var(--text2); cursor: pointer; display: flex; align-items: center; gap: 5px; }
  #dhNav .nv:hover { background: var(--bg); color: var(--text); }
  #dhNav .nv.active { background: var(--accent); color: #fff; font-weight: 600; }
  #dhNav .nv.leaf { font-size: 12.5px; font-weight: 500; padding: 8px 10px; border-radius: 10px; }
"""
assert old in s
s = s.replace(old, new, 1)

# 2) header 加折叠按钮 + 浮动拉出按钮
old2 = """<select id="modelSelect" title="切换模型"></select>"""
new2 = """<button id="sideToggle" title="收起/展开功能栏"></button>
  <select id="modelSelect" title="切换模型"></select>"""
assert old2 in s
s = s.replace(old2, new2)
old3 = '<!-- 引用悬浮卡 -->'
new3 = '<button id="sidePull" title="拉出功能栏"></button>\n\n<!-- 引用悬浮卡 -->'
assert old3 in s
s = s.replace(old3, new3, 1)

# 3) renderDhNav 重写为模块树（父子级可折叠）
old4 = """function renderDhNav() {
  const menus = (AUTH.user && AUTH.user.menus) || [];
  const top = menus.filter(m => !m.parent_id).sort((a, b) => a.sort - b.sort);
  const aiKids = menus.filter(m => m.parent_id === 6).sort((a, b) => a.sort - b.sort);
  const aiMenu = top.find(m => m.id === 6);
  const html = top.map(m => {
    const isAi = m.id === 6;
    return `<span class="nv ${isAi ? 'active' : 'dim'}" data-path="${m.path || ''}" title="${isAi ? '智能问答' : '宿主业务页'}" onclick="navTo('${isAi ? '/ai/chat' : (m.path || '/dashboard')}')">${m.name}</span>`;
  }).join('') + aiKids.map(k =>
    `<span class="nv dim" onclick="navTo('${k.path || '/ai/chat'}')">· ${k.name}</span>`).join('');
  const nav = document.createElement('div');
  nav.id = 'dhNav';
  nav.innerHTML = html;
  const aside = document.querySelector('aside');
  aside.insertBefore(nav, aside.firstChild);
}"""
new4 = """let NAVEL = null;
function renderDhNav() {
  const menus = (AUTH.user && AUTH.user.menus) || [];
  const byParent = {};
  menus.forEach(m => (byParent[m.parent_id || 0] = byParent[m.parent_id || 0] || []).push(m));
  const tops = (byParent[0] || []).sort((a, b) => a.sort - b.sort);
  const cur = location.hash.replace('#', '') || '/ai/chat';
  const html = tops.map(m => {
    const kids = (byParent[m.id] || []).sort((a, b) => a.sort - b.sort);
    const isCur = cur.startsWith(m.path || '\\u0000');
    if (kids.length) {
      return `<div class="nvgroup${isCur ? ' open' : ''}">
        <div class="nvt" onclick="this.parentElement.classList.toggle('open')">${esc(m.name)}<span class="chev">▶</span></div>
        <div class="nvkids">${kids.map(k =>
          `<span class="nv${cur.startsWith(k.path) ? ' active' : ''}" onclick="navTo('${k.path}')">· ${esc(k.name)}</span>`).join('')}</div></div>`;
    }
    const target = m.id === 6 ? '/ai/chat' : (m.path || '/dashboard');
    return `<span class="nv leaf${cur.startsWith(m.path || '/none') && m.id !== 6 ? ' active' : (m.id === 6 && cur.startsWith('/ai') ? ' active' : '')}" onclick="navTo('${target}')">${esc(m.name)}</span>`;
  }).join('');
  if (!NAVEL) { NAVEL = document.createElement('div'); NAVEL.id = 'dhNav';
    document.querySelector('aside').insertBefore(NAVEL, document.querySelector('aside').firstChild); }
  NAVEL.innerHTML = html;
}

/* 抽屉收放 */
function initSideToggle() {
  $('sideToggle').innerHTML = '☰';
  $('sidePull').innerHTML = '☰';
  const aside = document.querySelector('aside');
  const sync = () => {
    const tucked = aside.classList.contains('tucked');
    $('sidePull').classList.toggle('show', tucked);
    localStorage.setItem('dh_tucked', tucked ? '1' : '');
  };
  $('sideToggle').onclick = () => { aside.classList.toggle('tucked'); sync(); };
  $('sidePull').onclick = () => { aside.classList.remove('tucked'); sync(); };
  if (localStorage.getItem('dh_tucked')) { aside.classList.add('tucked'); }
  sync();
}"""
assert old4 in s
s = s.replace(old4, new4)

# 4) enterApp 调 initSideToggle；route() 刷新后重渲染树
old5 = """  renderDhNav(); renderDhUser();
  await loadDicts();"""
new5 = """  renderDhNav(); renderDhUser(); initSideToggle();
  await loadDicts();"""
assert old5 in s
s = s.replace(old5, new5)
old6 = "  if (!chat) renderPage(path);"
new6 = "  if (NAVEL) renderDhNav();\n  if (!chat) renderPage(path);"
assert old6 in s
s = s.replace(old6, new6)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('drawer nav ok')
