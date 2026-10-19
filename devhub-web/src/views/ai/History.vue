<template>
  <el-card shadow="never">
    <template #header>会话历史（仅本人）</template>
    <el-table :data="convs" v-loading="loading">
      <el-table-column label="会话"><template #default="s">{{ s.row.title || s.row.id }}</template></el-table-column>
      <el-table-column prop="turnCount" label="轮次" width="70" />
      <el-table-column label="时间" width="150"><template #default="s">{{ (s.row.updateTime || '').replace('T', ' ').slice(5, 16) }}</template></el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="s">
          <el-button link type="primary" @click="view(s.row)">查看</el-button>
          <el-button link type="danger" @click="del(s.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="msgs.length" style="margin-top:18px">
      <el-divider>消息详情</el-divider>
      <div v-for="(m, i) in msgs" :key="i" style="margin:10px 0; padding:12px 14px; border-radius:12px; background:var(--el-fill-color-light); font-size:13px; white-space:pre-wrap">
        <div style="font-size:11.5px; color:var(--el-text-color-secondary); margin-bottom:6px">
          {{ m.role === 'USER' ? '🙋 我' : '🤖 AI · ' + (m.model || '') }}{{ m.faithScore != null ? ` · 忠实度 ${m.faithScore}` : '' }}
        </div>
        {{ (m.content || '').slice(0, 600) }}
      </div>
    </div>
  </el-card>
</template>

<script setup>
defineOptions({ name: 'HistoryPage' })
import { ref, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import api from '../../api'

const convs = ref([]), msgs = ref([]), loading = ref(false)

async function load() {
  loading.value = true
  try { convs.value = (await api.get('/v1/conversations')) || [] } finally { loading.value = false }
}
async function view(c) { msgs.value = (await api.get(`/v1/conversations/${c.id}/messages`)) || [] }
async function del(c) {
  await ElMessageBox.confirm('删除该会话及消息？')
  await api.delete(`/v1/conversations/${c.id}`)
  ElMessage.success('已删除'); msgs.value = []; load()
}
onMounted(load)
</script>
