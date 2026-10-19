# -*- coding: utf-8 -*-
import io
p = r'src\main\java\com\devhub\module\doc\controller\DocCenterController.java'
s = io.open(p, encoding='utf-8').read()
old = """    @OperLog(module = "文档中心", action = "调整分类")"""
new = """    /** 重建索引（借鉴 MaxKB 运维操作）：删旧向量→切片重嵌入→重写 Milvus */
    @OperLog(module = "文档中心", action = "重建索引")
    @PreAuthorize("hasAuthority('ai:kb:manage')")
    @PostMapping("/documents/{id}/reindex")
    public Result<String> reindex(@PathVariable String id) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) return Result.fail(404, "文档不存在");
        var chunks = chunkMapper.selectList(new LambdaQueryWrapper<com.devhub.module.ai.model.entity.DocumentChunkEntity>()
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getDocumentId, id)
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getChunkType, 0)
                .eq(com.devhub.module.ai.model.entity.DocumentChunkEntity::getDeleted, 0));
        if (chunks.isEmpty()) return Result.fail(400, "该文档无切片（解析失败的请重新上传）");
        milvusService.deleteByDocumentId(id);
        java.util.List<String> ids = new java.util.ArrayList<>(), contents = new java.util.ArrayList<>();
        java.util.List<String> docIds = new java.util.ArrayList<>(), names = new java.util.ArrayList<>();
        for (var c : chunks) { ids.add(c.getId()); contents.add(c.getContent()); docIds.add(id); names.add(doc.getFileName()); }
        java.util.List<java.util.List<Float>> vecs = modelRegistry.embedAllToVectors(contents);
        milvusService.insertVectors(ids, docIds, names, vecs, contents);
        doc.setStatus("completed"); documentMapper.updateById(doc);
        return Result.success("已重建 " + chunks.size() + " 条向量");
    }

    @OperLog(module = "文档中心", action = "调整分类")"""
assert old in s
s = s.replace(old, new, 1)
old2 = "    private final DocumentMapper documentMapper;"
assert old2 in s
s = s.replace(old2, old2 + """
    private final com.devhub.module.ai.mapper.DocumentChunkMapper chunkMapper;
    private final com.devhub.module.ai.infrastructure.vectordb.MilvusService milvusService;
    private final com.devhub.module.ai.infrastructure.llm.ChatModelRegistry modelRegistry;""", 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('reindex backend ok')

# 角色管理 API
p2 = r'src\main\java\com\devhub\module\system\controller\SystemController.java'
s2 = io.open(p2, encoding='utf-8').read()
old3 = "    // ---------- 个人中心：改密 ----------"
new3 = """    // ---------- 角色管理（交互抄 RuoYi） ----------
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/roles")
    public Result<List<Map<String, Object>>> roles() {
        return Result.success(jdbc.queryForList(
                "SELECT r.id, r.role_key, r.name, COUNT(DISTINCT rm.menu_id) AS menu_count " +
                "FROM sys_role r LEFT JOIN sys_role_menu rm ON rm.role_id = r.id " +
                "WHERE r.deleted = 0 GROUP BY r.id, r.role_key, r.name"));
    }

    /** 角色当前菜单权限（勾选回显） */
    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/roles/{id}/menus")
    public Result<List<Long>> roleMenus(@PathVariable Long id) {
        return Result.success(jdbc.queryForList(
                "SELECT menu_id FROM sys_role_menu WHERE role_id = ?", Long.class, id));
    }

    @OperLog(module = "系统管理", action = "保存角色权限")
    @PreAuthorize("hasAuthority('system:manage')")
    @PutMapping("/roles/{id}/menus")
    public Result<String> assignMenus(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        List<Long> menuIds = body.getOrDefault("menuIds", List.of());
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ?", id);
        for (Long mid : menuIds) jdbc.update("INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (?,?)", id, mid);
        return Result.success("已更新 " + menuIds.size() + " 项权限");
    }

    @PreAuthorize("hasAuthority('system:manage')")
    @GetMapping("/menus/all")
    public Result<List<Map<String, Object>>> allMenus() {
        return Result.success(jdbc.queryForList("SELECT id, parent_id, name, perm FROM sys_menu WHERE visible = 1 ORDER BY sort"));
    }

    // ---------- 个人中心：改密 ----------"""
assert old3 in s2
s2 = s2.replace(old3, new3, 1)
io.open(p2, 'w', encoding='utf-8', newline='\n').write(s2)
print('roles api ok')

# 前端：文档行"重建索引"按钮 + 系统页角色权限区
p3 = r'src\main\resources\static\index.html'
s3 = io.open(p3, encoding='utf-8').read()
old4 = "驳回</button>` : '-'}</td>"
assert old4 in s3
s3 = s3.replace(old4, "驳回</button>` : ''}<button class=\"rowact\" onclick=\"DCRIDX('${r.id}', this)\">重建索引</button></td>")
old5 = "  window.DCPG = p => { st.page = p; loadDocs(); };"
assert old5 in s3
s3 = s3.replace(old5, old5 + """
  window.DCRIDX = async (id, btn) => {
    btn.disabled = true; const t = btn.textContent; btn.textContent = '重建中…';
    try { alert(await api('/api/doc/documents/' + id + '/reindex', { method: 'POST' })); }
    catch (e) { alert(e.message); }
    btn.disabled = false; btn.textContent = t;
  };""")
old6 = "    // 用户表（管理员可见；普通用户只读）"
assert old6 in s3
role_js = """    // 角色权限管理（RuoYi 式勾选）
    async function loadRoles() {
      let rs; try { rs = await api('/api/system/roles'); } catch (e) { return; }
      const box = document.createElement('div');
      box.innerHTML = '<div class="pg-title" style="font-size:15px;margin-top:22px">角色权限</div><div id="syRoles">' +
        rs.map(r => '<div style="display:flex;justify-content:space-between;align-items:center;padding:9px 0;border-bottom:.5px solid var(--border);font-size:13px">' +
          '<span><b>' + esc(r.role_key) + '</b> · ' + esc(r.name) + ' <span style="color:var(--text2)">（' + r.menu_count + ' 项菜单权限）</span></span>' +
          '<button class="rowact" onclick="ROLECFG(' + r.id + ')">配置权限</button></div>').join('') + '</div>';
      $('syDept').parentElement.parentElement.insertBefore(box, $('syDept').parentElement);
    }
    window.ROLECFG = async rid => {
      let all, mineIds;
      try { all = await api('/api/system/menus/all'); mineIds = new Set(await api('/api/system/roles/' + rid + '/menus')); }
      catch (e) { alert(e.message); return; }
      openModal('配置角色权限', all.map(m =>
        '<label style="display:flex;align-items:center;gap:8px;margin:6px 0;font-size:13px;color:var(--text)">' +
        '<input type="checkbox" name="m" value="' + m.id + '" ' + (mineIds.has(m.id) ? 'checked' : '') + ' style="width:auto">' +
        (m.parent_id ? '&nbsp;└ ' : '') + esc(m.name) +
        (m.perm ? ' <span style="color:var(--text2);font-size:11px">' + esc(m.perm) + '</span>' : '') + '</label>').join(''),
        async () => {
          const ids = [...document.querySelectorAll('#modalBox input[name=m]:checked')].map(c => Number(c.value));
          try { await api('/api/system/roles/' + rid + '/menus', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ menuIds: ids }) }); location.reload(); return true; }
          catch (e) { alert(e.message); return false; }
        });
    };
    loadRoles();

    // 用户表（管理员可见；普通用户只读）"""
s3 = s3.replace(old6, role_js)
io.open(p3, 'w', encoding='utf-8', newline='\n').write(s3)
print('frontend ok')
