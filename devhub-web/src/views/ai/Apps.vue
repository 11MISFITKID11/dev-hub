<template>
  <div>
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:14px">
      <h3 style="margin:0">AI 应用</h3>
      <el-button type="primary" @click="openCreate">＋ 创建应用</el-button>
    </div>

    <el-row :gutter="14">
      <el-col v-for="a in apps" :key="a.id" :span="8" style="margin-bottom:14px">
        <el-card shadow="hover" style="border-radius:14px">
          <div style="display:flex; justify-content:space-between">
            <b>{{ a.name }}</b>
            <el-tag :type="a.enabled === 1 ? 'success' : 'info'" size="small">
              {{ a.enabled === 1 ? '运行中' : '已下线' }}
            </el-tag>
          </div>
          <div style="color:var(--el-text-color-secondary); font-size:12.5px; margin:8px 0; min-height:34px">
            {{ a.description || '暂无描述' }}
          </div>
          <div style="font-size:12px; color:var(--el-text-color-secondary)">appKey: {{ a.appKey }}</div>
          <div style="display:flex; gap:8px; margin-top:10px">
            <el-button size="small" @click="openEdit(a)">配置</el-button>
            <el-button size="small" @click="openKeys(a)">密钥</el-button>
            <el-button size="small" @click="openEmbed(a)">嵌入</el-button>
            <el-button v-if="a.enabled === 1" size="small" type="danger" plain @click="disable(a)">下线</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="!apps.length" description="还没有应用，点右上角创建一个" />

    <!-- 创建/编辑弹窗：分区表单 -->
    <el-dialog v-model="dlg" :title="form.id ? '编辑应用' : '创建应用'" width="560px">
      <el-form label-width="96px">
        <el-divider content-position="left">基本信息</el-divider>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
        <el-divider content-position="left">人设与开场白</el-divider>
        <el-form-item label="角色人设">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="3"
                    placeholder="例：你是公司 IT 服务台助手，回答简洁、给出操作步骤" />
        </el-form-item>
        <el-form-item label="开场白">
          <el-input v-model="form.openingGreeting" placeholder="挂件打开时显示的第一句话" />
        </el-form-item>
        <el-divider content-position="left">访问配置</el-divider>
        <el-form-item label="部门数据域">
          <el-select v-model="form.deptId" clearable placeholder="全公司（默认）" style="width:100%">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="每分钟限额"><el-input-number v-model="form.rateLimitPerMin" :min="1" :max="600" /></el-form-item>
        <el-form-item label="Referer白名单">
          <el-input v-model="form.allowedOrigins" placeholder="https://oa.corp.com,https://portal.corp.com（空=不限）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 密钥抽屉 -->
    <el-drawer v-model="keyDlg" :title="'API 密钥 — ' + (cur && cur.name)" size="44%">
      <el-button type="primary" size="small" style="margin-bottom:12px" @click="createKey">＋ 新建密钥</el-button>
      <el-alert v-if="freshSecret" type="warning" :closable="false" style="margin-bottom:12px"
                title="密钥仅此一次显示，请立即复制保存" />
      <el-input v-if="freshSecret" :model-value="freshSecret" readonly style="margin-bottom:12px">
        <template #append><el-button @click="copy(freshSecret)">复制</el-button></template>
      </el-input>
      <el-table :data="keys" size="small">
        <el-table-column prop="keyPrefix" label="前缀" width="110" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="lastUsedAt" label="最近使用" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" link @click="toggleKey(row)">{{ row.enabled === 1 ? '禁用' : '启用' }}</el-button>
            <el-button size="small" link type="danger" @click="deleteKey(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 嵌入代码 -->
    <el-dialog v-model="embedDlg" title="嵌入到你的系统" width="560px">
      <p style="font-size:13px; color:var(--el-text-color-secondary)">在任意内部系统的 HTML 里粘贴：</p>
      <el-input :model-value="embedCode" type="textarea" :rows="3" readonly />
      <template #footer><el-button type="primary" @click="copy(embedCode)">复制代码</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'AppsPage' })
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const apps = ref([]), depts = ref([])
const dlg = ref(false), keyDlg = ref(false), embedDlg = ref(false)
const form = ref({}), cur = ref(null), keys = ref([]), freshSecret = ref('')
const embedCode = ref('')

async function load() { apps.value = (await api.get('/apps', { params: { size: 50 } })).records || [] }
onMounted(async () => {
  load()
  try { depts.value = await api.get('/system/depts') } catch (e) { /* 无权限时忽略 */ }
})

function openCreate() {
  form.value = { rateLimitPerMin: 20 }
  dlg.value = true
}
function openEdit(a) { form.value = { ...a }; dlg.value = true }
async function save() {
  if (form.value.id) await api.put(`/apps/${form.value.id}`, form.value)
  else await api.post('/apps', form.value)
  ElMessage.success('已保存'); dlg.value = false; load()
}
async function disable(a) {
  await ElMessageBox.confirm(`下线后挂件与 API 立即不可用，确认下线「${a.name}」？`, '确认')
  await api.delete(`/apps/${a.id}`); load()
}
async function openKeys(a) {
  cur.value = a; freshSecret.value = ''
  keys.value = await api.get(`/apps/${a.id}/keys`)
  keyDlg.value = true
}
async function createKey() {
  const r = await api.post(`/apps/${cur.value.id}/keys`, { name: 'key-' + (keys.value.length + 1) })
  freshSecret.value = r.secret
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
async function toggleKey(row) {
  await api.put(`/apps/${cur.value.id}/keys/${row.id}`, null, { params: { enabled: row.enabled !== 1 } })
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
async function deleteKey(row) {
  await api.delete(`/apps/${cur.value.id}/keys/${row.id}`)
  keys.value = await api.get(`/apps/${cur.value.id}/keys`)
}
function openEmbed(a) {
  const base = location.origin
  embedCode.value = `<iframe src="${base}/#/embed/${a.appKey}" width="420" height="640" style="border:0;border-radius:14px" title="AI 助手"></iframe>`
  embedDlg.value = true
}
async function copy(text) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}
</script>
