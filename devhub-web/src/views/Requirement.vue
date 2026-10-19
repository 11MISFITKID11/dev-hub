<template>
  <div>
    <!-- Epic 进度卡 -->
    <el-row v-if="epicProgress.length" :gutter="14" style="margin-bottom:14px">
      <el-col :span="6" v-for="e in epicProgress" :key="e.id">
        <el-card shadow="never" class="epic-card" @click="epicFilter = e.id; reset()">
          <div style="font-weight:600; font-size:13.5px; margin-bottom:8px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap" :title="e.name">📘 {{ e.name }}</div>
          <el-progress :percentage="e.percent" :stroke-width="8" :color="'#0071e3'" />
          <div style="font-size:11.5px; color:var(--el-text-color-secondary); margin-top:6px">{{ e.done }}/{{ e.total }} Story 完成</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <div class="table-toolbar">
        <ProjectSelect v-model="f.projectId" width="170px" @update:model-value="onProject" />
        <el-select v-model="f.epicId" placeholder="Epic：全部" clearable style="width:160px" @change="reset">
          <el-option v-for="e in epics" :key="e.id" :label="e.title" :value="e.id" />
        </el-select>
        <el-select v-model="f.status" placeholder="状态：全部" clearable style="width:130px" @change="reset">
          <el-option v-for="s in REQ_STATUS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建需求</el-button>
      </div>
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column label="所属 Epic" width="130"><template #default="s">{{ epicName(s.row.parentId) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="s">
          <el-tag size="small" :type="({OPEN:'info',DEVELOPING:'primary',DONE:'success',REJECTED:'danger'})[s.row.status]">
            {{ ({OPEN:'待开发',DEVELOPING:'开发中',DONE:'已完成',REJECTED:'已拒绝'})[s.row.status] || s.row.status }}
          </el-tag>
        </template></el-table-column>
        <el-table-column label="优先级" width="80"><template #default="s">
          <DictTag dict="task_priority" :value="s.row.priority" />
        </template></el-table-column>
        <el-table-column label="故事点" width="80" align="center"><template #default="s">{{ s.row.storyPoints ?? '—' }}</template></el-table-column>
        <el-table-column label="负责人" width="100"><template #default="s">{{ opts.userName(s.row.ownerUsername) }}</template></el-table-column>
        <el-table-column prop="createTime" label="创建" width="105"><template #default="s">{{ (s.row.createTime || '').slice(5, 10) }}</template></el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="s">
            <el-button link type="primary" @click="edit(s.row)">编辑</el-button>
            <el-button link type="primary" @click="showTasks(s.row)">任务</el-button>
            <el-button link type="danger" @click="del(s.row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无需求">
            <el-button type="primary" @click="openCreate">创建第一个需求</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :total="total" v-model:current-page="page" v-model:page-size="size" @current-change="load" @size-change="reset" />
    </el-card>

    <el-dialog v-model="dlgV" :title="form.id ? '编辑需求' : '新建需求'" width="480">
      <el-form ref="formRef" label-width="80px" :rules="rules" :model="form">
        <el-form-item label="类型">
          <el-radio-group v-model="form.rtype" :disabled="!!form.id">
            <el-radio-button value="EPIC">Epic（分组）</el-radio-button>
            <el-radio-button value="STORY">Story（需求）</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" prop="title"><el-input v-model="form.title" placeholder="如：用户登录模块" /></el-form-item>
        <el-form-item v-if="form.rtype === 'STORY'" label="所属Epic">
          <el-select v-model="form.parentId" clearable placeholder="不挂 Epic" style="width:100%">
            <el-option v-for="e in epics" :key="e.id" :label="e.title" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目" prop="projectId"><ProjectSelect v-model="form.projectId" width="100%" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in REQ_STATUS" :key="s.value" :label="s.label" :value="s.value" /></el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority"><el-option v-for="o in store.dicts.task_priority || []" :key="o.value" :label="o.label" :value="o.value" /></el-select>
        </el-form-item>
        <el-form-item v-if="form.rtype === 'STORY'" label="故事点">
          <el-select v-model="form.storyPoints" clearable placeholder="未估算">
            <el-option v-for="p in [1, 2, 3, 5, 8, 13]" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人"><UserSelect v-model="form.ownerUsername" width="100%" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="支持 Markdown" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlgV = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template>
    </el-dialog>

    <el-drawer v-model="tasksV" :title="'关联任务：' + (curReq.title || '')" size="46%">
      <el-table :data="reqTasks" size="small">
        <el-table-column prop="title" label="任务" show-overflow-tooltip />
        <el-table-column label="状态" width="100"><template #default="s"><DictTag dict="task_status" :value="s.row.status" /></template></el-table-column>
        <el-table-column label="指派" width="100"><template #default="s">{{ opts.userName(s.row.assigneeUsername) }}</template></el-table-column>
        <el-table-column prop="dueDate" label="截止" width="100" />
      </el-table>
      <div v-if="!reqTasks.length" style="padding:30px 0"><el-empty description="尚未挂任务——在任务弹窗中选择本需求即可关联" :image-size="80" /></div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useCrudList } from '../composables/useCrudList'
import { useUserStore } from '../stores/user'
import { useOptionsStore } from '../stores/options'
import DictTag from '../components/DictTag.vue'
import UserSelect from '../components/UserSelect.vue'
import ProjectSelect from '../components/ProjectSelect.vue'

defineOptions({ name: 'RequirementPage' })

const store = useUserStore(), opts = useOptionsStore()
const REQ_STATUS = [
  { label: '待开发', value: 'OPEN' }, { label: '开发中', value: 'DEVELOPING' },
  { label: '已完成', value: 'DONE' }, { label: '已拒绝', value: 'REJECTED' }
]
const f = ref({})
const { rows, total, page, size, loading, load, reset } = useCrudList('/requirements', () => ({ ...f.value, rtype: 'STORY' }))
const formRef = ref(null), dlgV = ref(false), form = ref({ rtype: 'STORY', status: 'OPEN', priority: 'M' }), saving = ref(false)
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }]
}
const epics = ref([]), epicProgress = ref([]), epicFilter = ref(null)
const tasksV = ref(false), curReq = ref({}), reqTasks = ref([])

const epicName = id => (epics.value.find(e => e.id === id) || {}).title || '—'

async function loadEpics() {
  epics.value = (await api.get('/requirements/epics', { params: { projectId: f.value.projectId || undefined } })) || []
  if (f.value.projectId) {
    epicProgress.value = (await api.get('/requirements/epic-progress', { params: { projectId: f.value.projectId } })) || []
  } else epicProgress.value = []
}
function onProject() { epicFilter.value = null; f.value.epicId = null; loadEpics(); reset() }

function openCreate() { form.value = { rtype: 'STORY', status: 'OPEN', priority: 'M', projectId: f.value.projectId }; dlgV.value = true }
function edit(r) { form.value = { ...r }; dlgV.value = true }
async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.value.id) await api.put('/requirements/' + form.value.id, form.value)
    else await api.post('/requirements', form.value)
    dlgV.value = false; load(); loadEpics()
  } finally { saving.value = false }
}
async function del(r) {
  await ElMessageBox.confirm(`删除「${r.title}」？${r.rtype === 'EPIC' ? '其下 Story 将解除分组（不删除）。' : ''}`)
  await api.delete('/requirements/' + r.id); load(); loadEpics()
}
async function showTasks(r) {
  curReq.value = r; tasksV.value = true
  reqTasks.value = (await api.get(`/requirements/${r.id}/tasks`)) || []
}

onMounted(() => { load(); opts.load(); loadEpics() })
</script>

<style scoped>
.epic-card { cursor: pointer; transition: transform .15s ease, box-shadow .15s ease; }
.epic-card:hover { transform: translateY(-2px); }
</style>
