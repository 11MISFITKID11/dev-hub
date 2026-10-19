<template>
  <div>
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-input v-model="kw" placeholder="项目名" style="width:220px" clearable @keyup.enter="reset" />
        <el-button type="primary" @click="openCreate">新建项目</el-button>
      </div>
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="name" label="项目" show-overflow-tooltip />
        <el-table-column prop="code" label="编码" width="100" />
        <el-table-column label="负责人" width="110"><template #default="s">{{ opts.userName(s.row.ownerUsername) }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s">
          <DictTag dict="project_status" :value="s.row.status" />
        </template></el-table-column>
        <el-table-column label="起止" width="200"><template #default="s">{{ s.row.startDate || '' }} ~ {{ s.row.endDate || '' }}</template></el-table-column>
        <el-table-column label="操作" width="350" fixed="right">
          <template #default="s">
            <el-button link type="primary" @click="edit(s.row)">编辑</el-button>
            <el-button link type="primary" @click="openIt(s.row)">迭代</el-button>
            <el-button link type="primary" @click="openMb(s.row)">成员</el-button>
            <el-button link type="primary" @click="openWf(s.row)">工作流</el-button>
            <el-button link type="primary" @click="openAuto(s.row)">自动化</el-button>
            <el-button link type="danger" @click="del(s.row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无项目">
            <el-button type="primary" @click="openCreate">新建第一个项目</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :total="total" v-model:current-page="page" v-model:page-size="size" @current-change="load" @size-change="reset" />
    </el-card>

    <el-dialog v-model="dlgV" :title="form.id ? '编辑项目' : '新建项目'" width="460">
      <el-form ref="formRef" label-width="80px" :rules="rules" :model="form">
        <el-form-item label="项目名" prop="name"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="负责人"><UserSelect v-model="form.ownerUsername" width="100%" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="进行中" :value="0" /><el-option label="归档" :value="1" /></el-select></el-form-item>
        <el-form-item label="开始"><el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="结束"><el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlgV = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template>
    </el-dialog>

    <el-dialog v-model="itV" :title="`迭代管理：${cur?.name || ''}`" width="560">
      <el-table :data="its" size="small">
        <el-table-column prop="name" label="迭代" />
        <el-table-column label="周期" width="180"><template #default="s">{{ s.row.startDate || '' }} ~ {{ s.row.endDate || '' }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="s">
          <el-tag :type="s.row.status === 1 ? 'info' : 'success'" size="small">{{ s.row.status === 1 ? '已关闭' : '进行中' }}</el-tag>
        </template></el-table-column>
        <el-table-column label="操作" width="190"><template #default="s">
          <el-button link type="primary" @click="showBurn(s.row)">燃尽图</el-button>
          <el-button v-if="s.row.status !== 1" link type="warning" @click="closeIt(s.row)">关闭</el-button>
          <el-button link type="danger" @click="delIt(s.row)">删除</el-button>
        </template></el-table-column>
        <el-table-column label="缺陷清点" width="190"><template #default="s">
          <span v-if="itSum[s.row.id]" style="font-size:12px">
            新增{{ itSum[s.row.id].total }} · 解决{{ itSum[s.row.id].fixed }} · 遗留{{ itSum[s.row.id].open }}
          </span>
        </template></el-table-column>
      </el-table>
      <div style="display:flex; gap:8px; margin-top:12px">
        <el-input v-model="itForm.name" placeholder="新迭代名" />
        <el-date-picker v-model="itForm.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" style="width:150px" />
        <el-date-picker v-model="itForm.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束" style="width:150px" />
        <el-button type="primary" @click="addIt">添加</el-button>
      </div>
    </el-dialog>

    <el-dialog v-model="mbV" :title="`成员管理：${cur?.name || ''}`" width="480">
      <el-table :data="mbs" size="small">
        <el-table-column label="成员"><template #default="s">{{ opts.userName(s.row.username) }}</template></el-table-column>
        <el-table-column prop="roleKey" label="角色" width="90" />
        <el-table-column width="70"><template #default="s"><el-button link type="danger" @click="delMb(s.row)">移除</el-button></template></el-table-column>
      </el-table>
      <div style="display:flex; gap:8px; margin-top:12px">
        <UserSelect v-model="mbForm.username" />
        <el-select v-model="mbForm.roleKey" placeholder="角色" style="width:120px"><el-option label="开发" value="DEV" /><el-option label="测试" value="QA" /></el-select>
        <el-button type="primary" @click="addMb">添加</el-button>
      </div>
    </el-dialog>

    <el-dialog v-model="burnV" :title="'燃尽与速率：' + ((burn.iteration || {}).name || '')" width="640">
      <!-- 理想线 vs 实际线（P0-3③）：SVG 双折线 -->
      <div v-if="(burn.actual || []).length" class="burn-chart">
        <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" style="width:100%; height:200px">
          <polyline :points="idealPts" fill="none" stroke="#909399" stroke-width="2" stroke-dasharray="6 4" />
          <polyline :points="actualPts" fill="none" stroke="#0071e3" stroke-width="2.5" stroke-linejoin="round" />
          <circle v-for="(p, i) in actualPoints" :key="i" :cx="p.x" :cy="p.y" r="3.5" fill="#0071e3" />
        </svg>
        <div class="burn-x">
          <span v-for="(p, i) in (burn.actual || [])" :key="i">{{ (p.date || '').slice(5) }}</span>
        </div>
        <div style="font-size:11px; color:var(--el-text-color-secondary); margin-top:6px">
          <span style="color:#0071e3">━ 实际剩余</span> · <span style="color:#909399">╌ 理想线</span>（按迭代周期线性递减）
        </div>
      </div>
      <el-empty v-else description="该迭代暂无燃尽数据" :image-size="70" />
      <el-divider>近 5 迭代速率（完成任务数）</el-divider>
      <div style="display:flex; gap:8px">
        <div v-for="(v, i) in burn.velocity || []" :key="i" style="flex:1; text-align:center; font-size:12px">
          <div :style="{ height: (v.done * 18 + 4) + 'px', background: 'var(--el-color-primary)', borderRadius: '4px 4px 0 0', margin: '0 auto', width: '60%' }"></div>
          <b>{{ v.done }}</b><div style="font-size:10px; color:#909399">{{ v.iteration.slice(0, 6) }}</div>
        </div>
      </div>
    </el-dialog>

    <!-- 工作流配置（P1-5）：状态集 + 流转规则 + WIP 限制 -->
    <el-dialog v-model="wfV" :title="`工作流配置：${cur?.name || ''}`" width="640">
      <el-divider content-position="left">状态集</el-divider>
      <el-table :data="wfStates" size="small">
        <el-table-column prop="name" label="状态" width="110" />
        <el-table-column prop="category" label="类别" width="90">
          <template #default="s">
            <el-tag size="small" :type="({TODO:'info',DOING:'primary',DONE:'success'})[s.row.category]">{{ s.row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="WIP 上限" width="90"><template #default="s">{{ s.row.wipLimit ?? '—' }}</template></el-table-column>
        <el-table-column label="初始态" width="70"><template #default="s">{{ s.row.isDefaultStart === 1 ? '✓' : '' }}</template></el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="s">
            <el-button link type="danger" :disabled="s.row.isDefaultStart === 1" @click="delState(s.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display:flex; gap:8px; margin-top:10px; flex-wrap:wrap">
        <el-input v-model="wfForm.name" placeholder="状态名（如 REVIEW）" style="width:150px" />
        <el-select v-model="wfForm.category" placeholder="类别" style="width:100px">
          <el-option label="TODO" value="TODO" /><el-option label="DOING" value="DOING" /><el-option label="DONE" value="DONE" />
        </el-select>
        <el-input-number v-model="wfForm.wipLimit" :min="0" placeholder="WIP" style="width:110px" />
        <el-button type="primary" @click="addState">添加状态</el-button>
      </div>

      <el-divider content-position="left">流转规则</el-divider>
      <div style="display:flex; flex-wrap:wrap; gap:8px">
        <el-tag v-for="t in wfTrans" :key="t.id" closable type="info" effect="light" @close="delTrans(t)">
          {{ t.fromState }} → {{ t.toState }}
        </el-tag>
        <span v-if="!wfTrans.length" style="color:var(--el-text-color-secondary); font-size:12px">暂无规则</span>
      </div>
      <div style="display:flex; gap:8px; margin-top:10px">
        <el-select v-model="wfT.from" placeholder="从" style="width:130px">
          <el-option v-for="s in wfStates" :key="s.id" :label="s.name" :value="s.name" />
        </el-select>
        <span style="align-self:center; color:var(--el-text-color-secondary)">→</span>
        <el-select v-model="wfT.to" placeholder="到" style="width:130px">
          <el-option v-for="s in wfStates" :key="s.id" :label="s.name" :value="s.name" />
        </el-select>
        <el-button type="primary" @click="addTrans">添加规则</el-button>
      </div>
    </el-dialog>
    <!-- 自动化规则（P2）：任务事件 → 站内通知/自动指派/设优先级 -->
    <el-dialog v-model="autoV" :title="`自动化规则：${cur?.name || ''}`" width="640">
      <el-table :data="autoRules" size="small">
        <el-table-column prop="name" label="规则" min-width="110" show-overflow-tooltip />
        <el-table-column label="触发条件" width="150"><template #default="s">{{ triggerLabel(s.row) }}</template></el-table-column>
        <el-table-column label="动作" width="150"><template #default="s">{{ actionLabel(s.row) }}</template></el-table-column>
        <el-table-column label="启用" width="60"><template #default="s">
          <el-switch :model-value="s.row.enabled === 1" @change="toggleRule(s.row)" />
        </template></el-table-column>
        <el-table-column label="操作" width="60"><template #default="s">
          <el-button link type="danger" @click="delRule(s.row)">删除</el-button>
        </template></el-table-column>
        <template #empty><el-empty description="暂无规则" :image-size="55" /></template>
      </el-table>
      <el-divider content-position="left">新增规则</el-divider>
      <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center">
        <el-input v-model="ruleForm.name" placeholder="规则名" style="width:120px" />
        <el-select v-model="ruleForm.triggerType" placeholder="触发" style="width:135px">
          <el-option label="任务创建时" value="TASK_CREATED" />
          <el-option label="状态变更时" value="STATUS_CHANGED" />
          <el-option label="指派变更时" value="ASSIGNEE_CHANGED" />
        </el-select>
        <el-select v-if="ruleForm.triggerType === 'STATUS_CHANGED'" v-model="ruleForm.triggerValue" placeholder="目标状态" style="width:115px">
          <el-option label="任意状态" value="*" />
          <el-option v-for="st in autoStates" :key="st.name" :label="st.name" :value="st.name" />
        </el-select>
        <el-select v-model="ruleForm.actionType" placeholder="动作" style="width:115px">
          <el-option label="发站内通知" value="NOTIFY" />
          <el-option label="自动指派" value="AUTO_ASSIGN" />
          <el-option label="设优先级" value="SET_PRIORITY" />
        </el-select>
        <UserSelect v-if="ruleForm.actionType === 'NOTIFY' || ruleForm.actionType === 'AUTO_ASSIGN'" v-model="ruleForm.actionValue" width="140px" />
        <el-select v-else-if="ruleForm.actionType === 'SET_PRIORITY'" v-model="ruleForm.actionValue" placeholder="优先级" style="width:95px">
          <el-option v-for="o in prioDict" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="addRule">添加</el-button>
      </div>
      <div style="font-size:11px; color:var(--el-text-color-secondary); margin-top:8px">
        例：任务创建时 → 发通知给指定成员；状态变更为 DONE 时 → 通知验收人。规则失败不影响业务。
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useCrudList, debounce } from '../composables/useCrudList'
import { useOptionsStore } from '../stores/options'
import DictTag from '../components/DictTag.vue'
import UserSelect from '../components/UserSelect.vue'

defineOptions({ name: 'ProjectList' })

const opts = useOptionsStore()
const kw = ref('')
const { rows, total, page, size, loading, load, reset } = useCrudList('/projects', () => ({ keyword: kw.value || undefined }))
const formRef = ref(null), dlgV = ref(false), form = ref({}), saving = ref(false)
const rules = { name: [{ required: true, message: '请输入项目名', trigger: 'blur' }] }
const itV = ref(false), its = ref([]), itForm = ref({}), cur = ref(null)
const mbV = ref(false), mbs = ref([]), mbForm = ref({})

function openCreate() { form.value = {}; dlgV.value = true }
function edit(r) { form.value = { ...r }; dlgV.value = true }
async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) await api.put('/projects/' + form.value.id, form.value)
    else await api.post('/projects', form.value)
    dlgV.value = false
    load()
    opts.load(true) // 新建/编辑可能改变项目下拉与名称映射
  } finally { saving.value = false }
}
async function del(r) {
  await ElMessageBox.confirm(`删除项目「${r.name}」？`)
  await api.delete('/projects/' + r.id); load()
}
const itSum = ref({}), burnV = ref(false), burn = ref({})
// 工作流配置状态
const wfV = ref(false), wfStates = ref([]), wfTrans = ref([])
const wfForm = ref({ category: 'DOING' }), wfT = ref({})
// 燃尽 SVG 折线几何
const W = 560, H = 170, PAD = 8
const maxOpen = computed(() => Math.max(...(burn.value.actual || []).map(p => p.total || p.open || 0), ...(burn.value.ideal || []).map(p => p.open || 0), 1))
const seriesPts = arr => {
  const a = arr || []
  if (a.length < 1) return ''
  const step = a.length === 1 ? 0 : (W - PAD * 2) / (a.length - 1)
  return a.map((p, i) => `${PAD + i * step},${H - PAD - (p.open / maxOpen.value) * (H - PAD * 2)}`).join(' ')
}
const actualPts = computed(() => seriesPts(burn.value.actual))
const idealPts = computed(() => seriesPts(burn.value.ideal))
const actualPoints = computed(() => {
  const a = burn.value.actual || []
  if (!a.length) return []
  const step = a.length === 1 ? 0 : (W - PAD * 2) / (a.length - 1)
  return a.map((p, i) => ({ x: PAD + i * step, y: H - PAD - (p.open / maxOpen.value) * (H - PAD * 2) }))
})
async function openIt(r) {
  cur.value = r; its.value = await api.get(`/projects/${r.id}/iterations`); itV.value = true
  for (const i of its.value) {
    try { itSum.value[i.id] = await api.get(`/defects/iteration/${i.id}/summary`) } catch (e) {}
  }
}
async function showBurn(i) {
  burn.value = await api.get(`/report/iterations/${i.id}/burndown`)
  burnV.value = true
}
async function closeIt(i) {
  await ElMessageBox.confirm(`关闭迭代「${i.name}」？未完成任务将移回 backlog 并通知项目成员。`)
  const msg = await api.post(`/projects/iterations/${i.id}/close`)
  ElMessage.success(msg || '已关闭')
  openIt(cur.value)
}
async function addIt() {
  if (!itForm.value.name) return
  await api.post(`/projects/${cur.value.id}/iterations`, itForm.value)
  itForm.value = {}; openIt(cur.value)
}
async function delIt(i) { await api.delete(`/projects/iterations/${i.id}`); openIt(cur.value) }
async function openMb(r) { cur.value = r; mbs.value = await api.get(`/projects/${r.id}/members`); mbV.value = true }
async function addMb() {
  if (!mbForm.value.username) return
  await api.post(`/projects/${cur.value.id}/members`, mbForm.value)
  mbForm.value = {}; openMb(cur.value)
}
async function delMb(m) { await api.delete(`/projects/${cur.value.id}/members/${m.username}`); openMb(cur.value) }

// ---- 工作流配置（P1-5）----
async function loadWf() {
  const d = await api.get(`/projects/${cur.value.id}/workflow`)
  wfStates.value = d.states || []
  wfTrans.value = d.transitions || []
}
async function openWf(r) {
  cur.value = r; wfV.value = true
  wfForm.value = { category: 'DOING' }; wfT.value = {}
  loadWf()
}
async function addState() {
  if (!wfForm.value.name) { ElMessage.warning('请输入状态名'); return }
  await api.post(`/projects/${cur.value.id}/workflow/states`, {
    name: wfForm.value.name, category: wfForm.value.category,
    wipLimit: wfForm.value.wipLimit || null
  })
  wfForm.value = { category: 'DOING' }
  loadWf()
}
async function delState(s) {
  await ElMessageBox.confirm(`删除状态「${s.name}」及其相关流转规则？`)
  await api.delete(`/projects/${cur.value.id}/workflow/states/${s.id}`)
  loadWf()
}
async function addTrans() {
  if (!wfT.value.from || !wfT.value.to) { ElMessage.warning('请选择流转的两端状态'); return }
  await api.post(`/projects/${cur.value.id}/workflow/transitions`, { from: wfT.value.from, to: wfT.value.to })
  wfT.value = {}
  loadWf()
}
async function delTrans(t) {
  await api.delete(`/projects/${cur.value.id}/workflow/transitions/${t.id}`)
  loadWf()
}

// ---- 自动化规则（P2）----
const autoV = ref(false), autoRules = ref([]), autoStates = ref([])
const ruleForm = ref({ triggerType: 'TASK_CREATED', triggerValue: '*', actionType: 'NOTIFY' })
const prioDict = [
  { label: '低', value: 'L' }, { label: '中', value: 'M' }, { label: '高', value: 'H' }, { label: '紧急', value: 'U' }
]
const triggerLabel = r => ({
  TASK_CREATED: '任务创建时',
  STATUS_CHANGED: '状态变更为 ' + (r.triggerValue === '*' ? '任意' : r.triggerValue),
  ASSIGNEE_CHANGED: '指派变更时'
})[r.triggerType] || r.triggerType
const actionLabel = r => ({
  NOTIFY: '通知 ' + (r.actionValue || ''),
  AUTO_ASSIGN: '指派给 ' + (r.actionValue || ''),
  SET_PRIORITY: '优先级→' + (r.actionValue || '')
})[r.actionType] || r.actionType
async function openAuto(r) {
  cur.value = r; autoV.value = true
  ruleForm.value = { triggerType: 'TASK_CREATED', triggerValue: '*', actionType: 'NOTIFY' }
  autoRules.value = (await api.get(`/projects/${r.id}/automation`)) || []
  try {
    const wf = await api.get(`/projects/${r.id}/workflow`)
    autoStates.value = wf.states || []
  } catch (e) { autoStates.value = [] }
}
async function addRule() {
  if (!ruleForm.value.name) { ElMessage.warning('请输入规则名'); return }
  if ((ruleForm.value.actionType === 'NOTIFY' || ruleForm.value.actionType === 'AUTO_ASSIGN') && !ruleForm.value.actionValue) {
    ElMessage.warning('请选择通知/指派目标成员'); return
  }
  await api.post(`/projects/${cur.value.id}/automation`, ruleForm.value)
  openAuto(cur.value)
}
async function toggleRule(r) {
  await api.put(`/projects/${cur.value.id}/automation/${r.id}/toggle`)
  autoRules.value = (await api.get(`/projects/${cur.value.id}/automation`)) || []
}
async function delRule(r) {
  await api.delete(`/projects/${cur.value.id}/automation/${r.id}`)
  autoRules.value = (await api.get(`/projects/${cur.value.id}/automation`)) || []
}

// 关键字搜索防抖 300ms（避免每击键一次请求）
watch(kw, debounce(() => reset(), 300))

onMounted(() => { load(); opts.load() })
</script>

<style scoped>
.burn-chart { padding: 4px 0; }
.burn-x { display: flex; justify-content: space-between; font-size: 10px; color: var(--el-text-color-secondary); padding: 4px 8px 0; }
</style>
