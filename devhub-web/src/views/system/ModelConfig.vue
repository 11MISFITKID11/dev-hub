<template>
  <el-card shadow="never">
    <template #header>
      <div style="display:flex; align-items:center; justify-content:space-between">
        <div>
          <span>AI 模型配置</span>
          <span style="font-size:12px; color:var(--el-text-color-secondary); margin-left:10px">
            变更后热生效（无需重启）· 密钥留空时继承环境变量，密钥不回显 · 向量模型维度须与 Milvus 集合一致
          </span>
        </div>
        <el-button type="primary" size="small" @click="openDlg()">新增模型</el-button>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading">
      <el-table-column label="类型" width="76" align="center">
        <template #default="s">
          <el-tag :type="s.row.modelType === 'embedding' ? 'success' : 'primary'" size="small">
            {{ s.row.modelType === 'embedding' ? '向量' : '对话' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="别名" width="130">
        <template #default="s">
          {{ s.row.name }}
          <el-tag v-if="s.row.name === primary && s.row.modelType !== 'embedding'" type="primary" size="small" style="margin-left:6px">主模型</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模型 ID" width="150">
        <template #default="s">{{ s.row.model }}<span v-if="s.row.dimensions" class="dh-num" style="color:var(--el-text-color-secondary); font-size:11.5px"> · {{ s.row.dimensions }}d</span></template>
      </el-table-column>
      <el-table-column prop="provider" label="供应商" width="90">
        <template #default="s">{{ s.row.provider }}</template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="Base URL" min-width="200" show-overflow-tooltip />
      <el-table-column label="密钥" width="110" align="center">
        <template #default="s">
          <el-tag v-if="s.row.keySource === 'env'" size="small" type="success">环境变量</el-tag>
          <el-tag v-else-if="s.row.keySource === 'db'" size="small" type="warning">库内配置</el-tag>
          <el-tag v-else size="small" type="danger">未配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="温度" width="64" align="center">
        <template #default="s">
          <span v-if="s.row.modelType === 'embedding'" style="color:var(--el-text-color-placeholder)">—</span>
          <span v-else class="dh-num">{{ (s.row.temperature ?? 0.7).toFixed(1) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="s">
          <el-switch :model-value="s.row.enabled === 1" :disabled="s.row.isPrimary === 1"
                     inline-prompt active-text="启用" inactive-text="停用" @change="toggle(s.row)" />
          <el-tag v-if="!s.row.active" size="small" type="info" style="margin-left:6px">未激活</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="s">
          <el-button link type="primary" size="small" @click="openDlg(s.row)">编辑</el-button>
          <el-button v-if="s.row.isPrimary !== 1 && s.row.modelType !== 'embedding'" link type="success" size="small" @click="setPrimary(s.row)">设为主模型</el-button>
          <el-button v-if="s.row.isPrimary !== 1" link type="danger" size="small" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top:12px; font-size:12.5px; color:var(--el-text-color-secondary)">
      当前路由：主模型 <b>{{ primary }}</b> · 降级链 <span class="dh-num">{{ (fallbackChain || []).join(' → ') || '—' }}</span>
    </div>

    <el-dialog v-model="dlg" :title="form.id ? '编辑模型' : '新增模型'" width="520px">
      <el-form label-width="88px">
        <el-form-item label="别名">
          <el-input v-model="form.name" :disabled="!!form.id" placeholder="如 glm-4-flash（路由用，唯一）" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.modelType" :disabled="!!form.id" style="width:180px">
            <el-option value="chat" label="对话模型" />
            <el-option value="embedding" label="向量模型" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="form.provider" style="width:180px">
            <el-option value="zhipu" label="智谱" />
            <el-option value="deepseek" label="DeepSeek" />
            <el-option value="qwen" label="通义千问" />
            <el-option value="other" label="其他（OpenAI 兼容）" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型 ID"><el-input v-model="form.model" placeholder="如 glm-4-flash" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" placeholder="https://open.bigmodel.cn/api/paas/v4" /></el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.id ? '留空 = 保持现有配置' : '留空 = 继承环境变量'" />
        </el-form-item>
        <el-form-item label="温度">
          <div style="display:flex; align-items:center; gap:10px; width:100%">
            <el-slider v-model="form.temperature" :min="0" :max="1" :step="0.1" style="flex:1" />
            <span class="dh-num" style="width:24px">{{ form.temperature.toFixed(1) }}</span>
          </div>
        </el-form-item>
        <el-form-item label="Max Tokens"><el-input-number v-model="form.maxTokens" :min="256" :max="32768" :step="256" :disabled="form.modelType === 'embedding'" /></el-form-item>
        <el-form-item v-if="form.modelType === 'embedding'" label="向量维度">
          <el-input-number v-model="form.dimensions" :min="256" :max="4096" :step="128" />
          <span style="margin-left:8px; font-size:12px; color:var(--el-text-color-secondary)">须与 Milvus 集合维度一致</span>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" maxlength="120" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
defineOptions({ name: 'ModelConfigPage' })
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const rows = ref([]), primary = ref(''), fallbackChain = ref([]), loading = ref(false)
const dlg = ref(false), saving = ref(false)
const form = ref({})

async function load() {
  loading.value = true
  try {
    const d = await api.get('/v1/admin/models')
    rows.value = d.rows || []
    primary.value = d.primary
    fallbackChain.value = d.fallbackChain || []
  } finally { loading.value = false }
}
function openDlg(row) {
  form.value = row
    ? { id: row.id, name: row.name, provider: row.provider, model: row.model, baseUrl: row.baseUrl,
        apiKey: '', temperature: row.temperature ?? 0.7, maxTokens: row.maxTokens ?? 4096,
        modelType: row.modelType || 'chat', dimensions: row.dimensions ?? null, remark: row.remark }
    : { id: null, name: '', provider: 'zhipu', model: '', baseUrl: '', apiKey: '', temperature: 0.7,
        maxTokens: 4096, modelType: 'chat', dimensions: null, remark: '' }
  dlg.value = true
}
async function save() {
  const f = form.value
  if (!f.model || !f.baseUrl) return ElMessage.warning('模型 ID 与 Base URL 必填')
  if (!f.id && !f.name) return ElMessage.warning('别名必填')
  saving.value = true
  try {
    if (f.id) {
      await api.put(`/v1/admin/models/${f.id}`, { ...f, apiKey: f.apiKey || null })
    } else {
      await api.post('/v1/admin/models', f)
    }
    ElMessage.success('已保存并热生效')
    dlg.value = false
    load()
  } finally { saving.value = false }
}
async function toggle(row) {
  const d = await api.post(`/v1/admin/models/${row.id}/toggle`)
  ElMessage.success(d || 'ok')
  load()
}
async function setPrimary(row) {
  await ElMessageBox.confirm(`把主模型切换为 ${row.name}？新问答将立即走该模型。`, '切换主模型', { type: 'warning' })
  const d = await api.post(`/v1/admin/models/${row.id}/primary`)
  ElMessage.success(d || 'ok')
  load()
}
async function del(row) {
  await ElMessageBox.confirm(`删除模型配置 ${row.name}？`, '删除', { type: 'warning' })
  const d = await api.delete(`/v1/admin/models/${row.id}`)
  ElMessage.success(d || 'ok')
  load()
}
onMounted(load)
</script>
