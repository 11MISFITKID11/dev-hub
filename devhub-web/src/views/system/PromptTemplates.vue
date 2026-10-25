<template>
  <el-card shadow="never">
    <template #header>
      <div style="display:flex; align-items:center; justify-content:space-between">
        <div>
          <span>Prompt 模板</span>
          <span style="font-size:12px; color:var(--el-text-color-secondary); margin-left:10px">
            各场景提示词运营：保存即热生效（{var} 占位符）；删除覆盖即恢复内置默认
          </span>
        </div>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" @row-click="pick" highlight-current-row
              style="cursor:pointer">
      <el-table-column prop="name" label="场景" width="150" />
      <el-table-column prop="scene" label="场景键" width="150" />
      <el-table-column label="状态" width="110" align="center">
        <template #default="s">
          <el-tag v-if="s.row.overridden" type="warning" size="small">已自定义</el-tag>
          <el-tag v-else type="info" size="small">内置默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="可用变量" min-width="220">
        <template #default="s"><span style="font-size:12.5px; color:var(--el-text-color-secondary)">{{ s.row.vars }}</span></template>
      </el-table-column>
      <el-table-column label="更新" width="150">
        <template #default="s">
          <span style="font-size:12.5px">{{ (s.row.updateBy || '') && (s.row.updateBy + ' · ') }}{{ (s.row.updateTime || '').replace('T', ' ').slice(0, 16) }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-divider />

    <template v-if="cur">
      <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:8px">
        <b>{{ cur.name }}（{{ cur.scene }}）</b>
        <div style="display:flex; gap:8px">
          <el-button size="small" @click="fillDefault">填入默认模板</el-button>
          <el-button size="small" type="danger" plain :disabled="!cur.overridden" @click="reset">恢复默认</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="save">保存并热生效</el-button>
        </div>
      </div>
      <el-input v-model="editContent" type="textarea" :rows="16" spellcheck="false"
                style="font-family: var(--dh-font-mono, monospace); font-size: 12.5px" />
      <div style="margin-top:8px; font-size:12px; color:var(--el-text-color-secondary)">
        变量：{{ cur.vars }}。改坏不担心——回答场景自动兜底金丝雀防注入标识，恢复默认一键回到出厂。
      </div>
    </template>
    <el-empty v-else description="选择上方场景查看/编辑模板" :image-size="70" />
  </el-card>
</template>

<script setup>
defineOptions({ name: 'PromptTemplatesPage' })
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const rows = ref([]), loading = ref(false), cur = ref(null)
const editContent = ref(''), saving = ref(false)

async function load() {
  loading.value = true
  try { rows.value = (await api.get('/v1/admin/prompts')) || [] } finally { loading.value = false }
}
function pick(row) {
  cur.value = row
  editContent.value = row.content || row.default || ''
}
function fillDefault() { editContent.value = cur.value.default }
async function save() {
  saving.value = true
  try {
    const msg = await api.put(`/v1/admin/prompts/${cur.value.scene}`, { content: editContent.value })
    ElMessage.success(msg || '已保存并热生效')
    load()
  } finally { saving.value = false }
}
async function reset() {
  await ElMessageBox.confirm(`恢复「${cur.value.name}」为内置默认模板？`, '恢复默认', { type: 'warning' })
  const msg = await api.put(`/v1/admin/prompts/${cur.value.scene}`, { content: '' })
  ElMessage.success(msg || '已恢复默认')
  cur.value.content = null
  editContent.value = cur.value.default
  load()
}
onMounted(load)
</script>
