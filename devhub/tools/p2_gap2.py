# -*- coding: utf-8 -*-
import io
p = r'src\main\resources\static\index.html'
s = io.open(p, encoding='utf-8').read()
# 文档行重建按钮 + 系统页角色权限（锚点放宽匹配）
old4 = "驳回</button>` : '-'}</td>"
assert old4 in s
s = s.replace(old4, "驳回</button>` : ''}<button class=\"rowact\" onclick=\"DCRIDX('${r.id}', this)\">重建索引</button></td>")
old5 = "  window.DCPG = p => { st.page = p; loadDocs(); };"
assert old5 in s
s = s.replace(old5, old5 + """
  window.DCRIDX = async (id, btn) => {
    btn.disabled = true; const t = btn.textContent; btn.textContent = '重建中…';
    try { alert(await api('/api/doc/documents/' + id + '/reindex', { method: 'POST' })); }
    catch (e) { alert(e.message); }
    btn.disabled = false; btn.textContent = t;
  };""")
import re
m = re.search(r'\n([ \t]*)// 用户表（管理员可见；普通用户只读）', s)
assert m, 'user-table comment not found'
line = m.group(0)
role_js = """
    // 角色权限管理（RuoYi 式勾选）
    async function loadRoles() {
      let rs; try { rs = await api('/api/system/roles'); } catch (e) { return; }
      const box = document.createElement('div');
      box.innerHTML = '<div class="pg-title" style="font-size:15px;margin-top:22px">角色权限</div>' +
        rs.map(r => '<div style="display:flex;justify-content:space-between;align-items:center;padding:9px 0;border-bottom:.5px solid var(--border);font-size:13px">' +
          '<span><b>' + esc(r.role_key) + '</b> · ' + esc(r.name) + '（' + r.menu_count + ' 项菜单权限）</span>' +
          '<button class="rowact" onclick="ROLECFG(' + r.id + ')">配置权限</button></div>').join('');
      $('syDept').parentElement.parentElement.insertBefore(box.firstChild ? box : box, $('syDept').parentElement);
    }
    window.ROLECFG = async rid => {
      let all, mineIds;
      try { all = await api('/api/system/menus/all'); mineIds = new Set(await api('/api/system/roles/' + rid + '/menus')); }
      catch (e) { alert(e.message); return; }
      openModal('配置角色权限', all.map(m =>
        '<label style="display:flex;align-items:center;gap:8px;margin:6px 0;font-size:13px;color:var(--text)">' +
        '<input type="checkbox" name="m" value="' + m.id + '" ' + (mineIds.has(m.id) ? 'checked' : '') + ' style="width:auto">' +
        (m.parent_id ? '└ ' : '') + esc(m.name) +
        (m.perm ? ' <span style="color:var(--text2);font-size:11px">' + esc(m.perm) + '</span>' : '') + '</label>').join(''),
        async () => {
          const ids = [...document.querySelectorAll('#modalBox input[name=m]:checked')].map(c => Number(c.value));
          try { await api('/api/system/roles/' + rid + '/menus', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ menuIds: ids }) }); location.reload(); return true; }
          catch (e) { alert(e.message); return false; }
        });
    };
    loadRoles();
""" + line
s = s.replace(line, role_js, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('frontend gap fixed')
