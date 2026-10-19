<template>
  <div>
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-select v-model="f.status" placeholder="状态：全部" clearable style="width:130px" @change="reset">
          <el-option v-for="o in store.dicts.task_status || []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="f.projectId" placeholder="项目：全部" clearable style="width:150px" @change="reset">
          <el-option v-for="p in opts.projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-select v-model="f.mine" placeholder="范围：全部" clearable style="width:120px" @change="reset">
          <el-option label="我的任务" value="true" />
        </el-select>
        <el-input v-model="f.keyword" placeholder="标题" style="width:180px" clearable @keyup.enter="reset" />
        <el-button type="primary" @click="openCreate">新建任务</el-button>
        <el-radio-group v-model="viewMode" size="small" @change="load">
          <el-radio-button value="list">列表</el-radio-button>
          <el-radio-button value="board">看板</el-radio-button>
          <el-radio-button value="calendar">日历</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 看板（列随项目工作流动态定义，P1-5） -->
      <div v-if="viewMode === 'board'" class="kb-grid" :style="{ gridTemplateColumns: 'repeat(' + kbCols.length + ', minmax(180px, 1fr))' }">
        <div v-for="col in kbCols" :key="col.k" class="kbcol" @dragover.prevent="col.hover = true" @dragleave="col.hover = false"
             @drop="drop(col.k)" :style="{ outline: col.hover ? '2px dashed var(--el-color-primary)' : 'none' }">
          <div class="kbcol-head">
            <span class="kbcol-dot" :style="{ background: col.color }"></span>
            <span>{{ col.label }}</span>
            <span v-if="col.wip" class="kbcol-count">（{{ board[col.k]?.length || 0 }}/{{ col.wip }}）</span>
            <span v-else class="kbcol-count">（{{ board[col.k]?.length || 0 }}）</span>
          </div>
          <div v-for="t in board[col.k] || []" :key="t.id" class="kbcard" draggable="true" @dragstart="dragId = t.id">
            <div style="display:flex; align-items:center; gap:6px">
              <span style="font-weight: 500; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap">{{ t.title }}</span>
              <DictTag dict="task_priority" :value="t.priority" />
            </div>
            <div style="color: var(--el-text-color-secondary); font-size: 11px; margin-top: 4px; display:flex; justify-content:space-between">
              <span>{{ opts.projectName(t.projectId) }} · {{ opts.userName(t.assigneeUsername) }}</span>
              <span v-if="t.dueDate">⏱ {{ t.dueDate }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 日历视图（P2）：按截止日聚合 -->
      <div v-if="viewMode === 'calendar'">
        <el-calendar v-model="calDate">
          <template #date-cell="{ data }">
            <div v-for="t in calTasksBy(data.day)" :key="t.id" class="cal-chip" @click="openDetail(t)">
              <DictTag dict="task_status" :value="t.status" />
              <span class="cal-chip-title">{{ t.title }}</span>
            </div>
          </template>
        </el-calendar>
      </div>

      <!-- 列表 -->
      <el-table v-if="viewMode === 'list'" :data="rows" v-loading="loading">
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column label="项目" width="130"><template #default="s">{{ opts.projectName(s.row.projectId) }}</template></el-table-column>
        <el-table-column label="指派给" width="100"><template #default="s">{{ opts.userName(s.row.assigneeUsername) }}</template></el-table-column>
        <el-table-column label="优先级" width="80"><template #default="s">
          <DictTag dict="task_priority" :value="s.row.priority" />
        </template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s">
          <DictTag dict="task_status" :value="s.row.status" />
        </template></el-table-column>
        <el-table-column prop="dueDate" label="截止" width="105" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="s">
            <el-button link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>
            <el-button v-if="s.row.status !== 'DONE'" link type="success" @click="adv(s.row, true)">推进</el-button>
            <el-button v-if="s.row.status !== 'TODO'" link type="warning" @click="adv(s.row, false)">回退</el-button>
            <el-button link type="danger" @click="del(s.row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无任务">
            <el-button type="primary" @click="openCreate">新建第一个任务</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination v-if="viewMode === 'list'" class="pager" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :total="total" v-model:current-page="page" v-model:page-size="size" @current-change="load" @size-change="reset" />
    </el-card>

    <el-dialog v-model="dlgV" :title="dlg.id ? '编辑任务' : '新建任务'" width="460">
      <el-form ref="formRef" label-width="80px" :rules="rules" :model="dlg">
        <el-form-item label="标题" prop="title"><el-input v-model="dlg.title" /></el-form-item>
        <el-form-item label="项目" prop="projectId"><ProjectSelect v-model="dlg.projectId" width="100%" /></el-form-item>
        <el-form-item label="指派给"><UserSelect v-model="dlg.assigneeUsername" width="100%" /></el-form-item>
        <el-form-item label="优先级"><el-select v-model="dlg.priority"><el-option v-for="o in store.dicts.task_priority || []" :key="o.value" :label="o.label" :value="o.value" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="dlg.status"><el-option v-for="o in store.dicts.task_status || []" :key="o.value" :label="o.label" :value="o.value" /></el-select></el-form-item>
        <el-form-item label="截止"><el-date-picker v-model="dlg.dueDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="需求">
          <el-select v-model="dlg.requirementId" clearable filterable placeholder="关联需求(Story)" style="width:100%">
            <el-option v-for="r in stories" :key="r.id" :label="r.title" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容"><el-input v-model="dlg.content" type="textarea" :rows="3" placeholder="支持 Markdown" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlgV = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template>
    </el-dialog>

    <el-drawer v-model="dtlV" :title="'任务：' + (detail.title || '')" size="42%">
      <el-tabs>
        <el-tab-pane label="详情">
          <div v-if="detail.requirementId" style="margin-bottom:10px">
            <el-tag effect="plain" type="primary">📘 {{ storyName(detail.requirementId) }}</el-tag>
          </div>
          <div v-if="detail.content" class="md" v-html="contentHtml"></div>
          <div v-else style="color:var(--el-text-color-secondary); font-size:12.5px; padding:8px 0">无描述</div>
        </el-tab-pane>
        <el-tab-pane :label="`子任务 ${subDone}/${subs.length}`">
          <div style="display:flex; gap:8px; margin-bottom:10px">
            <el-input v-model="newSub" placeholder="子任务标题，回车添加" size="small" @keyup.enter="addSub" />
            <el-button type="primary" size="small" @click="addSub">添加</el-button>
          </div>
          <div v-for="s in subs" :key="s.id" class="sub-item">
            <el-checkbox :model-value="s.done === 1" @change="toggleSub(s)">{{ '' }}</el-checkbox>
            <span :class="{ 'sub-done': s.done === 1 }" style="flex:1; font-size:13px">{{ s.title }}</span>
            <el-button link type="danger" size="small" @click="delSub(s)">删除</el-button>
          </div>
          <el-empty v-if="!subs.length" description="暂无子任务" :image-size="60" />
        </el-tab-pane>
        <el-tab-pane label="依赖">
          <div style="font-size:12px; color:var(--el-text-color-secondary); margin-bottom:6px">⛔ 被阻塞于（前置任务完成后本任务才能结项）</div>
          <div v-for="b in deps.blockedBy" :key="'b' + b.id" class="dep-item">
            <DictTag dict="task_status" :value="b.status" />
            <span style="flex:1; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap">#{{ b.id }} {{ b.title }}</span>
            <el-button link type="danger" size="small" @click="delDep(deps.depIds[b.id])">移除</el-button>
          </div>
          <el-empty v-if="!deps.blockedBy?.length" description="无前置依赖" :image-size="50" />
          <el-divider style="margin:10px 0" />
          <div style="font-size:12px; color:var(--el-text-color-secondary); margin-bottom:6px">🔁 阻塞中（等待本任务的其他任务）</div>
          <div v-for="b in deps.blocking" :key="'g' + b.id" class="dep-item">
            <DictTag dict="task_status" :value="b.status" />
            <span style="flex:1; font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap">#{{ b.id }} {{ b.title }}</span>
          </div>
          <el-empty v-if="!deps.blocking?.length" description="无" :image-size="50" />
          <el-divider style="margin:10px 0" />
          <div style="display:flex; gap:8px">
            <el-input v-model="newDepTitle" placeholder="输入前置任务 ID（数字）" size="small" style="width:200px" />
            <el-button type="primary" size="small" @click="addDep">添加前置依赖</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane :label="`工时 ${totalHours}h`">
          <div style="display:flex; gap:8px; margin-bottom:12px; align-items:center; flex-wrap:wrap">
            <el-input-number v-model="wlForm.hours" :min="0.5" :max="24" :step="0.5" size="small" style="width:110px" />
            <el-date-picker v-model="wlForm.date" type="date" value-format="YYYY-MM-DD" size="small" style="width:140px" placeholder="日期" />
            <el-input v-model="wlForm.note" placeholder="做了什么（备注）" size="small" style="flex:1; min-width:140px" />
            <el-button type="primary" size="small" @click="addWorklog">登记</el-button>
          </div>
          <div v-for="w in worklogs" :key="w.id" class="sub-item">
            <b style="width:52px; color: var(--el-color-primary)">{{ w.hours }}h</b>
            <span style="flex:1; font-size:12.5px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap">
              {{ w.workDate }} · {{ opts.userName(w.username) }}<template v-if="w.note"> · {{ w.note }}</template>
            </span>
            <el-button link type="danger" size="small" @click="delWorklog(w)">删除</el-button>
          </div>
          <el-empty v-if="!worklogs.length" description="尚未登记工时" :image-size="60" />
        </el-tab-pane>
        <el-tab-pane label="附件">
          <AttachmentList v-if="detail.id" obj-type="TASK" :obj-id="detail.id" />
        </el-tab-pane>
        <el-tab-pane label="时间线">
          <el-timeline>
            <el-timeline-item v-for="t in timeline" :key="t.kind + t.id"
              :timestamp="(t.time || '').replace('T', ' ').slice(0, 16)" :type="t.kind === 'comment' ? 'primary' : ''">
              <div v-if="t.kind === 'comment'"><b>{{ t.actor }}</b>：{{ t.content }}</div>
              <div v-else><b>{{ t.actor }}</b>
                <span v-if="t.action === 'status'"> 将状态 {{ t.oldValue }} 改为 <el-tag size="small" type="success">{{ t.newValue }}</el-tag></span>
                <span v-else-if="t.action === 'assign'"> 指派给 <el-tag size="small">{{ t.newValue }}</el-tag></span>
                <span v-else-if="t.action === 'create'"> 创建了任务</span>
                <span v-else-if="t.action === 'comment'"> 发表了评论</span>
                <span v-else> 修改了 {{ t.field }}：{{ t.oldValue }} → {{ t.newValue }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>
        <el-tab-pane label="关联缺陷">
          <el-table :data="taskDefects" size="small">
            <el-table-column prop="title" label="缺陷" />
            <el-table-column prop="severity" label="严重级" width="90" />
            <el-table-column prop="status" label="状态" width="90" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="评论">
          <div v-for="c in comments" :key="c.id" style="padding:8px 0; border-bottom:1px solid var(--el-border-color-lighter); font-size:13px">
            <b>{{ c.actor }}</b> <span style="color:var(--el-text-color-secondary); font-size:11px">{{ (t2s(c.time) || '').slice(5, 16) }}</span>
            <div style="margin-top:4px; white-space:pre-wrap">{{ c.content }}</div>
          </div>
          <el-input v-model="newComment" type="textarea" :rows="2" placeholder="评论（@用户名 可提及）" style="margin-top:10px" />
          <el-button type="primary" size="small" style="margin-top:8px" @click="postComment">发表</el-button>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import api from '../api'
import { useCrudList } from '../composables/useCrudList'
import { useUserStore } from '../stores/user'
import { useOptionsStore } from '../stores/options'
import DictTag from '../components/DictTag.vue'
import UserSelect from '../components/UserSelect.vue'
import ProjectSelect from '../components/ProjectSelect.vue'
import AttachmentList from '../components/AttachmentList.vue'

defineOptions({ name: 'TaskList' })

const store = useUserStore(), opts = useOptionsStore()
const f = ref({}), viewMode = ref('list'), dragId = ref(null), board = ref({})
const calDate = ref(new Date()), calTasks = ref([])
const calTasksBy = day => (calTasks.value || []).filter(t => t.dueDate === day).slice(0, 3)
const crud = useCrudList('/tasks', () => f.value)
const { rows, total, page, size, loading } = crud
const formRef = ref(null), dlg = ref({}), dlgV = ref(false), saving = ref(false)
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }]
}
const dtlV = ref(false), detail = ref({}), timeline = ref([]), comments = ref([]), taskDefects = ref([]), newComment = ref('')
const t2s = t => (t || '').replace('T', ' ')

// ---- 详情新元素：Markdown 渲染 / 子任务 / 依赖 / 需求 ----
const contentHtml = computed(() => DOMPurify.sanitize(marked.parse(detail.value.content || '', { breaks: true, gfm: true })))
const stories = ref([])
const storyName = id => (stories.value.find(s => s.id === id) || {}).title || '#' + id
const subs = ref([]), newSub = ref('')
const subDone = computed(() => subs.value.filter(s => s.done === 1).length)
const deps = ref({}), newDepTitle = ref('')
const worklogs = ref([]), wlForm = ref({ hours: 1, date: new Date().toISOString().slice(0, 10) })
const totalHours = computed(() => worklogs.value.reduce((s, w) => s + Number(w.hours || 0), 0).toFixed(1))

async function loadWorklogs(taskId) {
  worklogs.value = (await api.get(`/tasks/${taskId}/worklogs`)) || []
}
async function addWorklog() {
  if (!wlForm.value.hours) { ElMessage.warning('请填写工时'); return }
  await api.post(`/tasks/${detail.value.id}/worklogs`, {
    hours: wlForm.value.hours,
    workDate: wlForm.value.date || new Date().toISOString().slice(0, 10),
    note: wlForm.value.note
  })
  wlForm.value.note = ''
  loadWorklogs(detail.value.id)
}
async function delWorklog(w) {
  await api.delete(`/tasks/${detail.value.id}/worklogs/${w.id}`)
  loadWorklogs(detail.value.id)
}

async function loadStories() {
  try { stories.value = (await api.get('/requirements', { params: { rtype: 'STORY', size: 200 } })).records || [] }
  catch (e) { stories.value = [] }
}
async function loadSubs(taskId) {
  subs.value = (await api.get(`/tasks/${taskId}/subtasks`)) || []
}
async function addSub() {
  if (!newSub.value.trim()) return
  await api.post(`/tasks/${detail.value.id}/subtasks`, { title: newSub.value.trim() })
  newSub.value = ''
  loadSubs(detail.value.id)
}
async function toggleSub(s) {
  await api.put(`/tasks/${detail.value.id}/subtasks/${s.id}`, { done: s.done !== 1 })
  loadSubs(detail.value.id)
}
async function delSub(s) {
  await api.delete(`/tasks/${detail.value.id}/subtasks/${s.id}`)
  loadSubs(detail.value.id)
}
async function loadDeps(taskId) {
  deps.value = (await api.get(`/tasks/${taskId}/deps`)) || {}
}
async function addDep() {
  const id = Number(newDepTitle.value.trim())
  if (!id) { ElMessage.warning('请输入前置任务的数字 ID'); return }
  await api.post(`/tasks/${detail.value.id}/deps`, { dependsOn: id })
  newDepTitle.value = ''
  loadDeps(detail.value.id)
}
async function delDep(depId) {
  if (!depId) return
  await api.delete(`/tasks/${detail.value.id}/deps/${depId}`)
  loadDeps(detail.value.id)
}

function openCreate() { dlg.value = {}; dlgV.value = true }
async function openDetail(r) {
  detail.value = r; dtlV.value = true
  timeline.value = await api.get('/collab/TASK/' + r.id + '/timeline')
  comments.value = timeline.value.filter(t => t.kind === 'comment')
  taskDefects.value = await api.get('/defects/by-task/' + r.id)
  loadSubs(r.id)
  loadDeps(r.id)
  loadWorklogs(r.id)
}
async function postComment() {
  if (!newComment.value.trim()) return
  await api.post('/collab/TASK/' + detail.value.id + '/comments', { content: newComment.value })
  newComment.value = ''
  openDetail(detail.value)
}
const cols = ref([
  { k: 'TODO', label: '待办', color: '#909399', hover: false },
  { k: 'DOING', label: '进行中', color: 'var(--el-color-primary)', hover: false },
  { k: 'DONE', label: '已完成', color: 'var(--el-color-success)', hover: false }
])
const kbCols = ref([])
const CATEGORY_COLOR = { TODO: '#909399', DOING: 'var(--el-color-primary)', DONE: 'var(--el-color-success)' }

async function load() {
  if (viewMode.value === 'board') {
    board.value = await api.get('/tasks/board', { params: { projectId: f.value.projectId } })
    // 列定义：选中项目 → 其工作流状态；未选 → 接口返回键 + 默认三类别色
    if (f.value.projectId) {
      try {
        const wf = await api.get(`/projects/${f.value.projectId}/workflow`)
        kbCols.value = (wf.states || []).map(s => ({
          k: s.name, label: s.name, color: CATEGORY_COLOR[s.category] || '#909399',
          wip: s.wipLimit || 0, hover: false
        }))
      } catch (e) { kbCols.value = cols.value.map(c => ({ ...c })) }
    } else {
      const known = new Set(cols.value.map(c => c.k))
      kbCols.value = [
        ...cols.value.map(c => ({ ...c })),
        ...Object.keys(board.value).filter(k => !known.has(k))
          .map(k => ({ k, label: k, color: '#909399', hover: false }))
      ]
    }
  } else if (viewMode.value === 'calendar') {
    const d = await api.get('/tasks', { params: { page: 1, size: 200, projectId: f.value.projectId || undefined } })
    calTasks.value = d.records || []
  } else {
    await crud.load()
  }
}
function reset() { page.value = 1; load() }
async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (dlg.value.id) await api.put('/tasks/' + dlg.value.id, dlg.value)
    else await api.post('/tasks', dlg.value)
    dlgV.value = false; load()
  } finally { saving.value = false }
}
async function del(r) { await ElMessageBox.confirm(`删除任务「${r.title}」？`); await api.delete('/tasks/' + r.id); load() }
async function adv(r, fwd) {
  try {
    const next = await api.post(`/tasks/${r.id}/advance?forward=${fwd}`)
    ElMessage.success('已流转至：' + next)
  } catch (e) { /* 拦截已提示 */ }
  load()
}
async function drop(status) {
  kbCols.value.forEach(c => c.hover = false)
  if (!dragId.value) return
  try { await api.put('/tasks/' + dragId.value, { status }) } catch (e) { /* 已提示 */ }
  load()
}

onMounted(() => { load(); opts.load(); loadStories() })
</script>

<style scoped>
.cal-chip {
  display: flex; align-items: center; gap: 4px; padding: 1px 5px; margin: 1px 0;
  border-radius: 7px; font-size: 11px; cursor: pointer; overflow: hidden;
  background: rgba(0, 113, 227, 0.07);
  transition: background .15s ease;
}
.cal-chip:hover { background: rgba(0, 113, 227, 0.16); }
.cal-chip-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sub-item, .dep-item {
  display: flex; align-items: center; gap: 8px; padding: 7px 10px;
  border-radius: 10px; margin-bottom: 5px;
  background: var(--dh-glass-bg); border: 1px solid var(--dh-hairline);
}
.sub-done { text-decoration: line-through; color: var(--el-text-color-secondary); }
.md :deep(p) { margin: 0 0 8px; font-size: 13.5px; line-height: 1.8; }
.md :deep(pre) { background: #0d1117; color: #e6edf3; padding: 12px; border-radius: 12px; overflow-x: auto; font-size: 13px; }
.md :deep(code) { background: rgba(0,113,227,.1); padding: 1px 6px; border-radius: 5px; font-size: .9em; }
.md :deep(pre code) { background: none; padding: 0; }
.md :deep(ul), .md :deep(ol) { padding-left: 20px; font-size: 13.5px; line-height: 1.9; }
</style>
