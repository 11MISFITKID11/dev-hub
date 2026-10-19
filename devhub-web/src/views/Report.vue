<template>
  <div style="display:grid; gap:16px">
    <el-card shadow="never">
      <template #header>AI 用量（近 7 天，按日 / 用户 / 模型）</template>
      <el-table :data="rows" v-loading="loading">
        <el-table-column label="日期" width="100"><template #default="s">{{ String(s.row.stat_date).slice(5) }}</template></el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column prop="model" label="模型" width="130" />
        <el-table-column prop="prompt_tokens" label="输入" width="90" />
        <el-table-column prop="completion_tokens" label="输出" width="90" />
        <el-table-column label="合计" width="90"><template #default="s"><b>{{ s.row.total_tokens }}</b></template></el-table-column>
        <el-table-column label="占比">
          <template #default="s">
            <div style="background:linear-gradient(90deg,var(--el-color-primary),#42a5f5); height:8px; border-radius:4px"
                 :style="{ width: Math.max(3, s.row.total_tokens / max * 100) + '%' }" />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center">
          <span>AI 周报（自动聚合本周任务 / 缺陷 / 问答记录生成）</span>
          <el-button type="primary" size="small" :loading="wrLoading" @click="genWeekly">{{ wrLoading ? '生成中…' : (wr ? '重新生成' : '生成周报') }}</el-button>
        </div>
      </template>
      <div v-if="!wr && !wrLoading" style="padding:20px 0">
        <el-empty description="点击「生成周报」，AI 将基于你本周的客观数据生成 Markdown 周报" :image-size="80" />
      </div>
      <div v-else-if="wr" class="md" v-html="wrHtml"></div>
    </el-card>
  </div>
</template>

<script setup>
defineOptions({ name: 'ReportPage' })
import { ref, computed, onMounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import api from '../api'
const rows = ref([]), loading = ref(false)
const wr = ref(''), wrLoading = ref(false)
const max = computed(() => Math.max(...rows.value.map(r => r.total_tokens), 1))
const wrHtml = computed(() => DOMPurify.sanitize(marked.parse(wr.value, { breaks: true, gfm: true })))

async function genWeekly() {
  wrLoading.value = true
  try { wr.value = await api.post('/ai-assist/weekly-report') } finally { wrLoading.value = false }
}

onMounted(async () => { loading.value = true; try { rows.value = await api.get('/report/ai-usage', { params: { days: 7 } }) } finally { loading.value = false } })
</script>

<style scoped>
.md :deep(h1), .md :deep(h2), .md :deep(h3) { margin: 14px 0 8px; font-size: 15px; }
.md :deep(p) { margin: 0 0 8px; font-size: 13.5px; line-height: 1.8; }
.md :deep(ul), .md :deep(ol) { padding-left: 20px; font-size: 13.5px; line-height: 1.9; }
.md :deep(li) { margin: 2px 0; }
.md :deep(code) { background: rgba(0,113,227,.1); padding: 1px 6px; border-radius: 5px; font-size: .9em; }
</style>
