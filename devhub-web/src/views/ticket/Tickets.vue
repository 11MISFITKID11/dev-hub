<template>
  <div>
    <!-- 质量看板 -->
    <el-row :gutter="12" style="margin-bottom: 12px" v-if="stats">
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">未分派 / 处理中</div>
          <div class="st-v dh-num">
            <span :style="v.unassigned ? 'color:var(--el-color-warning)' : ''">{{ v.unassigned }}</span>
            <span style="font-size:13px;color:var(--el-text-color-secondary)"> / {{ cnt('PROCESSING') }}</span>
          </div>
        </div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">处理中</div><div class="st-v dh-num">{{ cnt('PROCESSING') }}</div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">平均满意度</div><div class="st-v dh-num">{{ (stats.overall.sat || 0).toFixed(1) }} / 5</div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card shadow="never">
        <div class="st"><div class="st-l">SLA 超时 / 未分派</div>
          <div class="st-v dh-num">
            <span :style="v.breached ? 'color:var(--el-color-danger)' : ''">{{ v.breached }}</span>
            <span style="font-size:13px;color:var(--el-text-color-secondary)"> / {{ v.unassigned }}</span>
          </div>
        </div>
      </el-card></el-col>
    </el-row>

    <!-- 快捷视图（SLA 运营） -->
    <el-tabs v-model="q.view" style="margin-bottom: 4px" @tab-change="p => { q.page = 1; load() }">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane name="unassigned"><template #label>未分派 <el-badge v-if="v.unassigned" :value="v.unassigned" /></template></el-tab-pane>
      <el-tab-pane name="risk"><template #label>超时风险 <el-badge v-if="v.risk" :value="v.risk" type="warning" /></template></el-tab-pane>
      <el-tab-pane name="breached"><template #label>已超时 <el-badge v-if="v.breached" :value="v.breached" type="danger" /></template></el-tab-pane>
      <el-tab-pane label="我的处理中" name="mine" />
    </el-tabs>

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
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="标签" width="110"><template #default="s">
        <el-tag v-for="tg in (tags[s.row.id] || []).slice(0, 2)" :key="tg.tagId" size="small" effect="plain"
                :color="tg.color" style="color:inherit; margin-right:4px; border:none">{{ tg.name }}</el-tag>
      </template></el-table-column>
      <el-table-column prop="username" label="发起人" width="84" />
      <el-table-column label="分类" width="96"><template #default="s">
        <el-tag size="small" :type="(CAT_STYLE[s.row.category] || 'info')">{{ CAT[s.row.category] || '未分类' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="优先级" width="80"><template #default="s">
        <el-tag size="small" :type="(PRI_STYLE[s.row.priority] || 'info')" effect="plain">{{ PRI[s.row.priority] || '-' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="SLA" width="96"><template #default="s">
        <el-tag v-if="slaRemain(s.row)" size="small" :type="slaRemain(s.row).cls" effect="light">{{ slaRemain(s.row).text }}</el-tag>
        <span v-else style="color:var(--el-text-color-placeholder)">-</span>
      </template></el-table-column>
      <el-table-column label="状态" width="84"><template #default="s">
        <el-tag size="small" :type="STATUS_STYLE[s.row.status]">{{ STATUS[s.row.status] }}</el-tag>
      </template></el-table-column>
      <el-table-column prop="handler" label="坐席" width="80" />
      <el-table-column label="质检" width="66"><template #default="s">
        <span v-if="s.row.qcScore != null" class="dh-num" :style="s.row.qcScore < 70 ? 'color:var(--el-color-danger)' : ''">{{ s.row.qcScore }}</span>
        <span v-else style="color:var(--el-text-color-placeholder)">-</span>
      </template></el-table-column>
      <el-table-column label="创建时间" width="110"><template #default="s">
        <span style="font-size:12px">{{ fmt(s.row.createTime) }}</span>
      </template></el-table-column>
      <el-table-column label="操作" width="80" fixed="right"><template #default="s">
        <el-button link type="primary" size="small" @click="open(s.row.id)">处理</el-button>
      </template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total"
      :page-size="q.size" :current-page="q.page" @current-change="p => { q.page = p; load() }" /></div>

    <!-- 处理抽屉 -->
    <el-drawer v-model="drawer" size="46%" :title="cur && cur.ticket.ticketNo + ' · ' + cur.ticket.title">
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
          <el-descriptions-item label="SLA">
            <template v-if="['PENDING','PROCESSING'].includes(cur.ticket.status)">
              <el-tag size="small" :type="slaRemain(cur.ticket)?.cls || 'info'">{{ slaDetail }}</el-tag>
              <span v-if="cur.ticket.slaPausedAt" style="font-size:11px;color:var(--el-text-color-secondary)"> · 等待客户暂停中</span>
            </template>
            <span v-else style="color:var(--el-text-color-secondary)">已终结</span>
          </el-descriptions-item>
          <el-descriptions-item label="标签">
            <el-select v-model="curTagIds" multiple size="small" style="width:100%" placeholder="选择标签"
                       @change="saveTags">
              <el-option v-for="tg in tagOptions" :key="tg.id" :label="tg.name" :value="tg.id" />
            </el-select>
          </el-descriptions-item>
        </el-descriptions>
        <div class="blk"><div class="blk-t">工单内容</div><div class="blk-b">{{ cur.ticket.content }}</div></div>

        <div class="blk"><div class="blk-t">处理往来</div>
          <el-timeline style="padding-left:2px; margin-top:8px">
            <el-timeline-item v-for="r in cur.replies" :key="r.id" :timestamp="fmt(r.createTime)"
              :type="r.role === 'agent' ? 'primary' : (r.role === 'system' ? 'warning' : (r.role === 'internal' ? 'warning' : 'info'))"
              :hollow="r.role === 'system'">
              <b>{{ r.author }}</b>（{{ ROLE_LBL[r.role] || r.role }}）：<span style="white-space:pre-wrap">{{ r.content }}</span>
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
          <div style="display:flex; gap:10px; margin-bottom:8px; align-items:center">
            <el-radio-group v-model="replyRole" size="small">
              <el-radio-button value="agent">回复客户</el-radio-button>
              <el-radio-button value="internal">内部备注</el-radio-button>
            </el-radio-group>
            <el-select v-model="transferTo" v-perm="'ticket:transfer'" placeholder="转派给…" clearable size="small" style="width:150px; margin-left:auto"
                       v-if="['PENDING','PROCESSING'].includes(cur.ticket.status)">
              <el-option v-for="a in agents" :key="a.username" :label="a.nickname || a.username" :value="a.username" />
            </el-select>
            <el-button v-if="transferTo && transferTo !== cur.ticket.handler" v-perm="'ticket:transfer'" size="small" @click="doTransfer">转派</el-button>
          </div>
          <el-input v-model="replyText" type="textarea" :rows="4"
                    :placeholder="replyRole === 'internal' ? '内部备注（仅坐席可见，不通知客户，不暂停 SLA）' : '回复内容（结单时作为解决方案记录）'" />
          <div style="display:flex; gap:8px; margin-top:8px; flex-wrap:wrap">
            <el-button @click="suggest" :loading="suggesting">✦ AI 建议</el-button>
            <el-button v-if="cur.ticket.status === 'PENDING'" type="primary" @click="take">受理</el-button>
            <el-button v-if="cur.ticket.status === 'PROCESSING'" type="success" @click="resolve">结单</el-button>
            <el-button v-if="cur.ticket.status === 'RESOLVED'" @click="closeTk">关闭</el-button>
            <el-button v-if="cur.ticket.status !== 'PENDING' || replyRole === 'internal'" @click="sendReply">
              {{ replyRole === 'internal' ? '保存备注' : '发送回复' }}
            </el-button>
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
import { useUserStore } from '../../stores/user'

const STATUS = { PENDING: '待受理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
const STATUS_STYLE = { PENDING: 'warning', PROCESSING: 'primary', RESOLVED: 'success', CLOSED: 'info' }
const ROLE_LBL = { agent: '坐席', customer: '客户', internal: '坐席内部备注', system: '系统' }
// 工单分类字典驱动（系统管理-字典维护，登录时已随 store 加载），空时回退内置
const catStore = useUserStore()
const CAT_FALLBACK = { PRODUCT: '产品咨询', BUG: '缺陷故障', ACCOUNT: '账号权限', BILLING: '计费账单', USAGE: '使用帮助', OTHER: '其他' }
const CAT = computed(() => {
  const d = {}
  ;((catStore.dicts || {}).ticket_category || []).forEach(x => { d[x.value] = x.label })
  return Object.keys(d).length ? d : CAT_FALLBACK
})
const CAT_STYLE = { PRODUCT: 'info', BUG: 'danger', ACCOUNT: 'warning', BILLING: 'warning', USAGE: 'info', OTHER: 'info' }
const PRI = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const PRI_STYLE = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const SRC = { manual: '手动创建', chat_refusal: 'AI 拒答转单', chat_dislike: '点踩转单', ai_agent: 'AI 代客建单' }
const fmt = s => (s || '').replace('T', ' ').slice(5, 16)

const q = ref({ page: 1, size: 10, status: '', category: '', priority: '', keyword: '', view: 'mine' })
const rows = ref([]), tags = ref({}), total = ref(0), loading = ref(false)
const stats = ref(null)
const drawer = ref(false), cur = ref(null)
const replyText = ref(''), suggesting = ref(false), qcing = ref(false)
const replyRole = ref('agent')
const transferTo = ref('')
const agents = ref([]), tagOptions = ref([]), curTagIds = ref([])

const cnt = k => { const it = (stats.value.byStatus || []).find(x => x.k === k); return it ? it.v : 0 }
const v = computed(() => {
  const m = { unassigned: 0, risk: 0, breached: 0 }
  ;((stats.value || {}).views || []).forEach(x => { m[x.k] = x.v })
  return m
})
const qcProblems = computed(() => {
  try { return JSON.parse(cur.value.ticket.qcResult || '{}').problems || '' } catch { return '' }
})
const slaDetail = computed(() => {
  if (!cur.value) return ''
  const t = cur.value.ticket
  const first = t.slaFirstAt ? '已首响' : '未首响'
  const r = slaRemain(t)
  return `${first} · ${r ? r.text : '-'}`
})

// SLA 剩余（解决时钟，含暂停顺延；前端仅展示，事实源在后端）
function slaRemain(row) {
  if (!['PENDING', 'PROCESSING'].includes(row.status)) return null
  if (row.slaBreach) return { text: '已超时', cls: 'danger' }
  if (row.slaPausedAt) return { text: '等待客户', cls: 'info' }
  if (!row.slaResolveDue) return null
  const due = new Date(row.slaResolveDue).getTime() + (row.slaPauseMs || 0)
  const ms = due - Date.now()
  if (ms < 0) return { text: '已超时', cls: 'danger' }
  const h = Math.floor(ms / 3600000), m = Math.ceil((ms % 3600000) / 60000)
  return { text: h > 0 ? `${h}h${m}m` : `${m}m`, cls: ms < 3600000 ? 'warning' : 'success' }
}

async function load() {
  loading.value = true
  try {
    const d = await api.get('/v1/tickets', { params: q.value })
    rows.value = d.records || []; total.value = Number(d.total || 0)
    tags.value = d.tags || {}
  } finally { loading.value = false }
}
async function loadStats() {
  try { stats.value = await api.get('/v1/tickets/stats') } catch { /* 看板失败不阻塞列表 */ }
}
async function open(id) {
  cur.value = await api.get(`/v1/tickets/${id}`)
  replyText.value = ''; replyRole.value = 'agent'; transferTo.value = ''
  curTagIds.value = (cur.value.tags || []).map(t => t.tagId)
  if (!agents.value.length) { try { agents.value = await api.get('/v1/tickets/agents') } catch { } }
  if (!tagOptions.value.length) { try { tagOptions.value = await api.get('/v1/tickets/tags') } catch { } }
  drawer.value = true
}
async function saveTags(vals) {
  const old = new Set((cur.value.tags || []).map(t => t.tagId))
  const now = new Set(vals)
  for (const t of now) if (!old.has(t)) await api.post(`/v1/tickets/${cur.value.ticket.id}/tags/${t}`)
  for (const t of old) if (!now.has(t)) await api.delete(`/v1/tickets/${cur.value.ticket.id}/tags/${t}`)
  cur.value.tags = tagOptions.value.filter(t => now.has(t)).map(t => ({ tagId: t.id, name: t.name, color: t.color }))
  load()
}
async function take() {
  try {
    cur.value.ticket = await api.post(`/v1/tickets/${cur.value.ticket.id}/take`)
    ElMessage.success('已受理')
    load()
  } catch (e) { ElMessage.error(e.message || '受理失败') }
}
async function doTransfer() {
  await ElMessageBox.confirm(`确认转派给 ${transferTo.value}？`, '转派')
  cur.value.ticket = await api.post(`/v1/tickets/${cur.value.ticket.id}/transfer`, { to: transferTo.value })
  ElMessage.success('已转派')
  transferTo.value = ''
  load(); open(cur.value.ticket.id)
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
  if (!replyText.value.trim()) return ElMessage.warning('请输入内容')
  await api.post(`/v1/tickets/${cur.value.ticket.id}/reply`,
    replyRole.value === 'internal' ? { content: replyText.value, role: 'internal' } : { content: replyText.value })
  ElMessage.success(replyRole.value === 'internal' ? '备注已保存' : '已发送'); replyText.value = ''
  open(cur.value.ticket.id); load()
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
