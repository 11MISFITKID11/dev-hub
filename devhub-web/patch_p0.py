# -*- coding: utf-8 -*-
import io

# 1) 路由加效能页
p = 'src/router/index.js'
s = io.open(p, encoding='utf-8').read()
if 'Metrics' not in s:
    s = s.replace("      { path: 'report', component: () => import('../views/Report.vue') },",
        "      { path: 'report', component: () => import('../views/Report.vue') },\n      { path: 'metrics', component: () => import('../views/Metrics.vue') },")
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('router ok')

# 2) 任务详情抽屉
p2 = 'src/views/Task.vue'
s2 = io.open(p2, encoding='utf-8').read()
if 'dtlV' not in s2:
    s2 = s2.replace(
        """            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>""",
        """            <el-button link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>""")
    drawer = (
        "\n    <el-drawer v-model=\"dtlV\" :title=\"'任务：' + (detail.title || '')\" size=\"42%\">\n"
        "      <el-tabs>\n"
        "        <el-tab-pane label=\"时间线\">\n"
        "          <el-timeline>\n"
        "            <el-timeline-item v-for=\"t in timeline\" :key=\"t.kind + t.id\"\n"
        "              :timestamp=\"(t.time || '').replace('T', ' ').slice(0, 16)\" :type=\"t.kind === 'comment' ? 'primary' : ''\">\n"
        "              <div v-if=\"t.kind === 'comment'\"><b>{{ t.actor }}</b>：{{ t.content }}</div>\n"
        "              <div v-else><b>{{ t.actor }}</b>\n"
        "                <span v-if=\"t.action === 'status'\"> 将状态 {{ t.oldValue }} 改为 <el-tag size=\"small\" type=\"success\">{{ t.newValue }}</el-tag></span>\n"
        "                <span v-else-if=\"t.action === 'assign'\"> 指派给 <el-tag size=\"small\">{{ t.newValue }}</el-tag></span>\n"
        "                <span v-else-if=\"t.action === 'create'\"> 创建了任务</span>\n"
        "                <span v-else-if=\"t.action === 'comment'\"> 发表了评论</span>\n"
        "                <span v-else> 修改了 {{ t.field }}：{{ t.oldValue }} → {{ t.newValue }}</span>\n"
        "              </div>\n"
        "            </el-timeline-item>\n"
        "          </el-timeline>\n"
        "        </el-tab-pane>\n"
        "        <el-tab-pane label=\"关联缺陷\">\n"
        "          <el-table :data=\"taskDefects\" size=\"small\">\n"
        "            <el-table-column prop=\"title\" label=\"缺陷\" />\n"
        "            <el-table-column prop=\"severity\" label=\"严重级\" width=\"90\" />\n"
        "            <el-table-column prop=\"status\" label=\"状态\" width=\"90\" />\n"
        "          </el-table>\n"
        "        </el-tab-pane>\n"
        "        <el-tab-pane label=\"评论\">\n"
        "          <div v-for=\"c in comments\" :key=\"c.id\" style=\"padding:8px 0; border-bottom:1px solid var(--el-border-color-lighter); font-size:13px\">\n"
        "            <b>{{ c.creator }}</b> <span style=\"color:var(--el-text-color-secondary); font-size:11px\">{{ (c.createTime || '').slice(5, 16) }}</span>\n"
        "            <div style=\"margin-top:4px; white-space:pre-wrap\">{{ c.content }}</div>\n"
        "          </div>\n"
        "          <el-input v-model=\"newComment\" type=\"textarea\" :rows=\"2\" placeholder=\"评论（@用户名 可提及）\" style=\"margin-top:10px\" />\n"
        "          <el-button type=\"primary\" size=\"small\" style=\"margin-top:8px\" @click=\"postComment\">发表</el-button>\n"
        "        </el-tab-pane>\n"
        "      </el-tabs>\n"
        "    </el-drawer>\n")
    s2 = s2.replace("  </div>\n</template>", drawer + "  </div>\n</template>", 1)
    s2 = s2.replace("const dlg = ref({}), dlgV = ref(false)",
        "const dlg = ref({}), dlgV = ref(false)\n"
        "const dtlV = ref(false), detail = ref({}), timeline = ref([]), comments = ref([]), taskDefects = ref([]), newComment = ref('')\n"
        "async function openDetail(r) {\n"
        "  detail.value = r; dtlV.value = true\n"
        "  timeline.value = await api.get('/collab/TASK/' + r.id + '/timeline')\n"
        "  comments.value = timeline.value.filter(t => t.kind === 'comment')\n"
        "  taskDefects.value = await api.get('/defects/by-task/' + r.id)\n"
        "}\n"
        "async function postComment() {\n"
        "  if (!newComment.value.trim()) return\n"
        "  await api.post('/collab/TASK/' + detail.value.id + '/comments', { content: newComment.value })\n"
        "  newComment.value = ''\n"
        "  openDetail(detail.value)\n"
        "}")
    io.open(p2, 'w', encoding='utf-8', newline='\n').write(s2)
    print('Task drawer ok')

# 3) 缺陷详情抽屉
p3 = 'src/views/Defect.vue'
s3 = io.open(p3, encoding='utf-8').read()
if 'dtlV' not in s3:
    s3 = s3.replace(
        """            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>""",
        """            <el-button link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>""")
    drawer3 = (
        "\n    <el-drawer v-model=\"dtlV\" :title=\"'缺陷：' + (detail.title || '')\" size=\"42%\">\n"
        "      <el-timeline>\n"
        "        <el-timeline-item v-for=\"t in timeline\" :key=\"t.kind + t.id\"\n"
        "          :timestamp=\"(t.time || '').replace('T', ' ').slice(0, 16)\" :type=\"t.kind === 'comment' ? 'primary' : ''\">\n"
        "          <div v-if=\"t.kind === 'comment'\"><b>{{ t.actor }}</b>：{{ t.content }}</div>\n"
        "          <div v-else><b>{{ t.actor }}</b>\n"
        "            <span v-if=\"t.action === 'status'\"> 状态 {{ t.oldValue }} → <el-tag size=\"small\" type=\"success\">{{ t.newValue }}</el-tag></span>\n"
        "            <span v-else-if=\"t.action === 'create'\"> 提交了缺陷</span>\n"
        "            <span v-else> {{ t.action }}</span>\n"
        "          </div>\n"
        "        </el-timeline-item>\n"
        "      </el-timeline>\n"
        "      <el-input v-model=\"newComment\" type=\"textarea\" :rows=\"2\" placeholder=\"评论…\" />\n"
        "      <el-button type=\"primary\" size=\"small\" style=\"margin-top:8px\" @click=\"postComment\">发表</el-button>\n"
        "    </el-drawer>\n")
    s3 = s3.replace("  </div>\n</template>", drawer3 + "  </div>\n</template>", 1)
    s3 = s3.replace("const dlg = ref({}), dlgV = ref(false)",
        "const dlg = ref({}), dlgV = ref(false)\n"
        "const dtlV = ref(false), detail = ref({}), timeline = ref([]), newComment = ref('')\n"
        "async function openDetail(r) {\n"
        "  detail.value = r; dtlV.value = true\n"
        "  timeline.value = await api.get('/collab/DEFECT/' + r.id + '/timeline')\n"
        "}\n"
        "async function postComment() {\n"
        "  if (!newComment.value.trim()) return\n"
        "  await api.post('/collab/DEFECT/' + detail.value.id + '/comments', { content: newComment.value })\n"
        "  newComment.value = ''; openDetail(detail.value)\n"
        "}")
    io.open(p3, 'w', encoding='utf-8', newline='\n').write(s3)
    print('Defect drawer ok')

# 4) 项目迭代弹窗：缺陷清点 + 燃尽图
p4 = 'src/views/Project.vue'
s4 = io.open(p4, encoding='utf-8').read()
if 'itSum' not in s4:
    s4 = s4.replace(
        '<el-table-column width="70"><template #d="s"><el-button link type="danger" @click="delIt(s.row)">删除</el-button></template></el-table-column>',
        '<el-table-column label="操作" width="130"><template #d="s">\n'
        '          <el-button link type="primary" @click="showBurn(s.row)">燃尽图</el-button>\n'
        '          <el-button link type="danger" @click="delIt(s.row)">删除</el-button>\n'
        '        </template></el-table-column>\n'
        '        <el-table-column label="缺陷清点" width="200"><template #d="s">\n'
        '          <span v-if="itSum[s.row.id]" style="font-size:12px">\n'
        '            新增{{ itSum[s.row.id].total }} · 解决{{ itSum[s.row.id].fixed }} · 遗留{{ itSum[s.row.id].open }}\n'
        '          </span>\n'
        '        </template></el-table-column>')
    s4 = s4.replace(
        "async function openIt(r) { cur.value = r; its.value = await api.get(`/projects/${r.id}/iterations`); itV.value = true }",
        "const itSum = ref({}), burnV = ref(false), burn = ref({})\n"
        "async function openIt(r) {\n"
        "  cur.value = r; its.value = await api.get(`/projects/${r.id}/iterations`); itV.value = true\n"
        "  for (const i of its.value) {\n"
        "    try { itSum.value[i.id] = await api.get(`/defects/iteration/${i.id}/summary`) } catch (e) {}\n"
        "  }\n"
        "}\n"
        "async function showBurn(i) {\n"
        "  burn.value = await api.get(`/report/iterations/${i.id}/burndown`)\n"
        "  burnV.value = true\n"
        "}")
    burndlg = (
        "\n    <el-dialog v-model=\"burnV\" :title=\"'燃尽与速率：' + ((burn.iteration || {}).name || '')\" width=\"620\">\n"
        "      <div style=\"display:flex; align-items:flex-end; gap:6px; height:160px; padding:6px 0 22px\">\n"
        "        <div v-for=\"(p, i) in burn.actual || []\" :key=\"i\" style=\"flex:1; display:flex; flex-direction:column; align-items:center; justify-content:flex-end; height:100%\">\n"
        "          <div :style=\"{ width: '60%', height: (p.open / Math.max(1, p.total) * 120) + 'px', background: 'linear-gradient(180deg,#f56c6c,#fab6b6)', borderRadius: '5px 5px 0 0' }\" :title=\"'剩余 ' + p.open\"></div>\n"
        "          <i style=\"font-style:normal; font-size:10px; color:#909399; margin-top:4px\">{{ p.date.slice(5) }}</i>\n"
        "        </div>\n"
        "      </div>\n"
        "      <el-divider>近 5 迭代速率（完成任务数）</el-divider>\n"
        "      <div style=\"display:flex; gap:8px\">\n"
        "        <div v-for=\"(v, i) in burn.velocity || []\" :key=\"i\" style=\"flex:1; text-align:center; font-size:12px\">\n"
        "          <div :style=\"{ height: (v.done * 18 + 4) + 'px', background: 'var(--el-color-primary)', borderRadius: '4px 4px 0 0', margin: '0 auto', width: '60%' }\"></div>\n"
        "          <b>{{ v.done }}</b><div style=\"font-size:10px; color:#909399\">{{ v.iteration.slice(0, 6) }}</div>\n"
        "        </div>\n"
        "      </div>\n"
        "    </el-dialog>\n")
    s4 = s4.replace("  </div>\n</template>", burndlg + "  </div>\n</template>", 1)
    io.open(p4, 'w', encoding='utf-8', newline='\n').write(s4)
    print('Project burndown ok')
