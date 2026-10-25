<template>
  <el-card shadow="never">
    <template #header>
      <div style="display:flex; align-items:center; justify-content:space-between">
        <span>会话记录评价 · 客户对 AI 回答的满意度审视</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>

    <!-- 筛选 -->
    <el-form inline @submit.prevent="load">
      <el-form-item label="用户">
        <el-input v-model="q.username" placeholder="用户名" clearable style="width:150px" @keyup.enter="load" />
      </el-form-item>
      <el-form-item label="评价">
        <el-select v-model="q.feedback" clearable placeholder="全部" style="width:120px">
          <el-option :value="1" label="有帮助" />
          <el-option :value="-1" label="无帮助" />
          <el-option :value="0" label="未评价" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间">
        <el-date-picker v-model="q.range" type="daterange" value-format="YYYY-MM-DD"
                        start-placeholder="开始" end-placeholder="结束" style="width:240px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rows" v-loading="loading">
      <el-table-column label="用户" width="100"><template #default="s">{{ s.row.username }}</template></el-table-column>
      <el-table-column label="问题" min-width="200" show-overflow-tooltip>
        <template #default="s">{{ s.row.question || '—' }}</template>
      </el-table-column>
      <el-table-column label="AI 回答" min-width="220" show-overflow-tooltip>
        <template #default="s">{{ plain(s.row.answer) }}</template>
      </el-table-column>
      <el-table-column label="评价" width="90" align="center">
        <template #default="s">
          <el-tag v-if="s.row.feedback === 1" type="success" size="small">有帮助</el-tag>
          <el-tag v-else-if="s.row.feedback === -1" type="danger" size="small">无帮助</el-tag>
          <span v-else style="color:var(--el-text-color-placeholder)">未评价</span>
        </template>
      </el-table-column>
      <el-table-column label="忠实度" width="80" align="center">
        <template #default="s">
          <span v-if="s.row.faithScore != null" class="dh-num">{{ (s.row.faithScore * 100).toFixed(0) }}%</span>
          <span v-else style="color:var(--el-text-color-placeholder)">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="model" label="模型" width="120" />
      <el-table-column label="时间" width="150">
        <template #default="s">{{ (s.row.createTime || '').replace('T', ' ').slice(0, 16) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="s">
          <el-button link type="primary" @click="open(s.row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top:14px; justify-content:flex-end" layout="total, prev, pager, next"
                   :total="total" :page-size="q.size" v-model:current-page="q.page" @current-change="load" />

    <!-- 全文详情 -->
    <el-drawer v-model="drawer" title="问答详情" size="42%">
      <template v-if="cur">
        <div class="qa-block">
          <div class="qa-lbl">客户提问</div>
          <div class="qa-q">{{ cur.question || '（未记录）' }}</div>
        </div>
        <div class="qa-block">
          <div class="qa-lbl">AI 回答（{{ cur.model }}）</div>
          <div class="qa-a">{{ stripMeta(cur.answer) }}</div>
        </div>
        <div class="qa-meta">
          <el-tag v-if="cur.feedback === 1" type="success">客户评价：有帮助</el-tag>
          <el-tag v-else-if="cur.feedback === -1" type="danger">客户评价：无帮助（已记知识缺口）</el-tag>
          <el-tag v-else type="info">未评价</el-tag>
          <el-tag v-if="cur.faithScore != null" type="warning">忠实度 {{ (cur.faithScore * 100).toFixed(0) }}%</el-tag>
        </div>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
defineOptions({ name: 'FeedbackPage' })
import { ref, onMounted } from 'vue'
import api from '../../api'

const rows = ref([]), total = ref(0), loading = ref(false)
const q = ref({ page: 1, size: 10, username: '', feedback: null, range: null })
const drawer = ref(false), cur = ref(null)

const plain = t => stripMeta(t).replace(/[#*`>\-]/g, '').replace(/\s+/g, ' ').slice(0, 120)
// 历史数据里回答可能内嵌 __META__{...} 元数据块（括号配对剥离）
function stripMeta(t) {
  if (!t || !t.startsWith('__META__')) return t || ''
  const s = t.indexOf('{')
  let depth = 0, inStr = false, esc = false
  for (let i = s; i < t.length; i++) {
    const c = t[i]
    if (esc) { esc = false; continue }
    if (c === '\\') { esc = true; continue }
    if (c === '"') inStr = !inStr
    if (inStr) continue
    if (c === '{') depth++
    else if (c === '}') { depth--; if (!depth) return t.slice(i + 1) }
  }
  return t
}

async function load() {
  loading.value = true
  try {
    const [s, e] = q.value.range || []
    const d = await api.get('/v1/chat/messages/page', {
      page: q.value.page, size: q.value.size,
      username: q.value.username || undefined,
      feedback: q.value.feedback === null ? undefined : q.value.feedback,
      start: s, end: e
    })
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } finally { loading.value = false }
}
function reset() {
  q.value = { page: 1, size: 10, username: '', feedback: null, range: null }
  load()
}
function open(row) { cur.value = row; drawer.value = true }
onMounted(load)
</script>

<style scoped>
.qa-block { margin-bottom: 18px; }
.qa-lbl { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.qa-q { padding: 10px 14px; border-radius: 10px; background: var(--el-fill-color-light); font-size: 13.5px; white-space: pre-wrap; }
.qa-a { padding: 10px 14px; border-radius: 10px; background: var(--el-color-primary-light-9); font-size: 13.5px; white-space: pre-wrap; line-height: 1.8; }
.qa-meta { display: flex; gap: 8px; }
</style>
