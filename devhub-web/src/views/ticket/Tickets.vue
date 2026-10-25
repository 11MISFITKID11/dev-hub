<template>
  <div>
    <!-- 质量看板 -->
    <el-row :gutter="12" style="margin-bottom: 12px" v-if="stats">
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">待受理</div><div class="st-v dh-num">{{ cnt('PENDING') }}</div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">处理中</div><div class="st-v dh-num">{{ cnt('PROCESSING') }}</div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">平均满意度</div><div class="st-v dh-num">{{ (stats.overall.sat || 0).toFixed(1) }} / 5</div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">AI 质检均分</div><div class="st-v dh-num">{{ (stats.overall.qc || 0).toFixed(0) }} / 100</div></div>
      </el-card></el-col>
    </el-row>

    <div class="table-toolbar">
      <el-select v-model="q.status" placeholder="状态" clearable style="width:110px" @change="load">
        <el-option v-for="(l, k) in STATUS" :key="k" :label="l" :value="k" />
      </el-select>
      <el-select v-model="q.category" placeholder="分类" clearable style="width:120px" @change="load">
        <el-option v-for="(l, k) in CAT" :key="k" :label="l" :value="k" />
      </el-select>
      <el-select v-model="q.priority" placeholder="优先级" clearable style="width:110px" @change="load">
        <el-option v-for="(l, k) in PRI" :key="k" :label="l" :value="k" />
      </el-select>
      <el-input v-model="q.keyword" placeholder="标题关键字" clearable style="width:180px" @keyup.enter="load" />
      <el-button @click="load">查询</el-button>
    </div>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="ticketNo" label="工单号" width="100" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="username" label="发起人" width="90" />
      <el-table-column label="分类" width="100"><template #default="s">
        <el-tag size="small" :type="(CAT_STYLE[s.row.category] || 'info')">{{ CAT[s.row.category] || '未分类' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="优先级" width="86"><template #default="s">
        <el-tag size="small" :type="(PRI_STYLE[s.row.priority] || 'info')" effect="plain">{{ PRI[s.row.priority] || '-' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="来源" width="96"><template #default="s">
        <span style="font-size:12px">{{ SRC[s.row.source] || s.row.source }}</span>
      </template></el-table-column>
      <el-table-column label="状态" width="88"><template #default="s">
        <el-tag size="small" :type="STATUS_STYLE[s.row.status]">{{ STATUS[s.row.status] }}</el-tag>
      </template></el-table-column>
      <el-table-column prop="handler" label="坐席" width="86" />
      <el-table-column label="满意度" width="76"><template #default="s">
        <span v-if="s.row.satisfaction" class="dh-num">{{ s.row.satisfaction }}★</span><span v-else style="color:var(--el-text-color-placeholder)">-</span>
      </template></el-table-column>
      <el-table-column label="质检" width="76"><template #default="s">
        <span v-if="s.row.qcScore != null" class="dh-num" :style="s.row.qcScore < 70 ? 'color:var(--el-color-danger)' : ''">{{ s.row.qcScore }}</span>
        <span v-else style="color:var(--el-text-color-placeholder)">-</span>
      </template></el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column label="操作" width="80" fixed="right"><template #default="s">
        <el-button link type="primary" size="small" @click="open(s.row.id)">处理</el-button>
      </template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total"
      :page-size="q.size" :current-page="q.page" @current-change="p => { q.page = p; load() }" /></div>

    <!-- 处理抽屉 -->
    <el-drawer v-model="drawer" size="46%" :title="cur && cur.ticketNo + ' · ' + cur.ticket.title">
      <template v-if="cur">
        <el-descriptions :column="2" size="small" border style="margin-bottom:12px">
          <el-descriptions-item label="发起人">{{ cur.ticket.username }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="STATUS_STYLE[cur.ticket.status]">{{ STATUS[cur.ticket.status] }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <el-tag size="small" :type="(CAT_STYLE[cur.ticket.category] || 'info')">{{ CAT[cur.ticket.category] || '未分类' }}</el-tag>
            <span v-if="cur.ticket.aiClassified" style="font-size:11px;color:var(--el-text-color-secondary)"> · AI 识别</span>
          </el-descriptions-item>
          <el-descriptions-item label="优先级">
            <el-tag size="small" :type="(PRI_STYLE[cur.ticket.priority] || 'info')" effect="plain">{{ PRI[cur.ticket.priority] || '-' }}</el-tag>
            <span v-if="cur.ticket.aiClassified" style="font-size:11px;color:var(--el-text-color-secondary)"> · AI 识别</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="blk"><div class="blk-t">工单内容</div><div class="blk-b">{{ cur.ticket.content }}</div></div>

        <div class="blk"><div class="blk-t">处理往来</div>
          <el-timeline style="padding-left:2px; margin-top:8px">
            <el-timeline-item v-for="r in cur.replies" :key="r.id" :timestamp="r.createTime"
              :type="r.role === 'agent' ? 'primary' : 'info'">
              <b>{{ r.author }}</b>（{{ r.role === 'agent' ? '坐席' : '客户' }}）：<span style="white-space:pre-wrap">{{ r.content }}</span>
            </el-timeline-item>
            <el-timeline-item v-if="!cur.replies.length" timestamp="暂无往来">等待坐席受理处理</el-timeline-item>
          </el-timeline>
        </div>

        <!-- AI 质检结果 -->
        <div class="blk" v-if="cur.ticket.qcScore != null">
          <div class="blk-t">AI 质检</div>
          <div class="blk-b">
            <b class="dh-num" :style="cur.ticket.qcScore < 70 ? 'color:var(--el-color-danger)' : ''">{{ cur.ticket.qcScore }}</b> / 100
            <span style="margin-left:8px">{{ qcProblems }}</span>
          </div>
        </div>

        <!-- 坐席操作区 -->
        <div style="border-top:1px solid var(--dh-hairline); padding-top:12px">
          <el-input v-model="replyText" type="textarea" :rows="4" placeholder="回复内容（结单时作为解决方案记录）" />
          <div style="display:flex; gap:8px; margin-top:8px; flex-wrap:wrap">
            <el-button @click="suggest" :loading="suggesting">✦ AI 建议</el-button>
            <el-button v-if="cur.ticket.status === 'PENDING'" type="primary" @click="take">受理</el-button>
            <el-button v-if="cur.ticket.status === 'PROCESSING'" type="success" @click="resolve">结单</el-button>
            <el-button v-if="cur.ticket.status === 'RESOLVED'" @click="closeTk">关闭</el-button>
            <el-button v-if="cur.ticket.status !== 'PENDING'" @click="sendReply">发送回复</el-button>
            <el-button v-if="cur.ticket.qcScore == null && (cur.ticket.status === 'RESOLVED' || cur.ticket.status === 'CLOSED')"
                       @click="reqc" :loading="qcing">重新质检</el-button>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const STATUS = { PENDING: '待受理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
const STATUS_STYLE = { PENDING: 'warning', PROCESSING: 'primary', RESOLVED: 'success', CLOSED: 'info' }
const CAT = { PRODUCT: '产品咨询', BUG: '缺陷故障', ACCOUNT: '账号权限', BILLING: '计费相关', USAGE: '使用帮助', OTHER: '其他' }
const CAT_STYLE = { PRODUCT: 'info', BUG: 'danger', ACCOUNT: 'warning', BILLING: 'warning', USAGE: 'info', OTHER: 'info' }
const PRI = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const PRI_STYLE = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const SRC = { manual: '手动创建', chat_refusal: 'AI 拒答转单', chat_dislike: '点踩转单' }

const q = ref({ page: 1, size: 10, status: '', category: '', priority: '', keyword: '' })
const rows = ref([]), total = ref(0), loading = ref(false)
const stats = ref(null)
const drawer = ref(false), cur = ref(null)
const replyText = ref(''), suggesting = ref(false), qcing = ref(false)

const cnt = k => { const it = (stats.value.byStatus || []).find(x => x.k === k); return it ? it.v : 0 }
const qcProblems = computed(() => {
  try { return JSON.parse(cur.value.ticket.qcResult || '{}').problems || '' } catch { return '' }
})

async function load() {
  loading.value = true
  try {
    const d = await api.get('/v1/tickets', { params: q.value })
    rows.value = d.records || []; total.value = Number(d.total || 0)
  } finally { loading.value = false }
}
async function loadStats() {
  try { stats.value = await api.get('/v1/tickets/stats') } catch { /* 看板失败不阻塞列表 */ }
}
async function open(id) {
  cur.value = await api.get(`/v1/tickets/${id}`)
  replyText.value = ''
  drawer.value = true
}
async function take() {
  cur.value.ticket = await api.post(`/v1/tickets/${cur.value.ticket.id}/take`)
  ElMessage.success('已受理')
  load()
}
async function suggest() {
  suggesting.value = true
  try {
    const draft = await api.get(`/v1/tickets/${cur.value.ticket.id}/suggest`)
    replyText.value = draft
    ElMessage.success('AI 已起草，请人工确认后发送')
  } finally { suggesting.value = false }
}
async function sendReply() {
  if (!replyText.value.trim()) return ElMessage.warning('请输入回复内容')
  await api.post(`/v1/tickets/${cur.value.ticket.id}/reply`, { content: replyText.value })
  ElMessage.success('已发送'); replyText.value = ''
  open(cur.value.ticket.id)
}
async function resolve() {
  if (!replyText.value.trim()) return ElMessage.warning('结单需填写解决方案')
  await ElMessageBox.confirm('确认结单？结单后将自动触发 AI 质检并通知发起人评价。', '结单')
  const t = await api.post(`/v1/tickets/${cur.value.ticket.id}/resolve`, { resolution: replyText.value })
  cur.value.ticket = t; replyText.value = ''
  ElMessage.success('已结单，AI 质检进行中')
  load(); loadStats()
}
async function closeTk() {
  await api.post(`/v1/tickets/${cur.value.ticket.id}/close`)
  cur.value.ticket.status = 'CLOSED'
  ElMessage.success('已关闭'); load()
}
async function reqc() {
  qcing.value = true
  try {
    await api.post(`/v1/tickets/${cur.value.ticket.id}/qc`)
    setTimeout(() => open(cur.value.ticket.id), 1500)
  } finally { qcing.value = false }
}
onMounted(() => { load(); loadStats() })
</script>

<style scoped>
.st { display: flex; flex-direction: column; gap: 4px; }
.st-l { font-size: 12px; color: var(--el-text-color-secondary); }
.st-v { font-size: 22px; font-weight: 700; }
.blk { margin-bottom: 14px; }
.blk-t { font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.blk-b { font-size: 13px; line-height: 1.7; white-space: pre-wrap; background: var(--el-fill-color-light); border-radius: 8px; padding: 10px 12px; }
</style>
