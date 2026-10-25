<template>
  <div>
    <!-- 统计卡片 -->
    <el-row :gutter="14">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="never" class="stat"><div class="num dh-num">{{ c.v }}</div><div class="lbl">{{ c.label }}</div></el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14" style="margin-top:14px">
      <!-- 满意度分布 -->
      <el-col :span="9">
        <el-card shadow="never">
          <template #header>满意度分布（1-5 星）</template>
          <div v-for="s in satRows" :key="s.star" class="sat-row">
            <span class="sat-lbl">{{ s.star }} 星</span>
            <div class="sat-bar"><div class="sat-fill" :style="{ width: s.pct + '%' }" /></div>
            <span class="sat-cnt dh-num">{{ s.cnt }}</span>
          </div>
          <el-empty v-if="!satTotal" description="暂无评价数据" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 质检明细表 -->
      <el-col :span="15">
        <el-card shadow="never">
          <template #header>
            <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px">
              <span>质检明细（低分优先）</span>
              <div style="display:flex; gap:8px; align-items:center">
                <el-select v-model="f.category" clearable placeholder="全部分类" size="small" style="width:120px" @change="loadRows">
                  <el-option v-for="(v, k) in CAT" :key="k" :value="k" :label="v" />
                </el-select>
                <el-select v-model="f.band" clearable placeholder="全部分数段" size="small" style="width:120px" @change="loadRows">
                  <el-option value="low" label="低分（<70）" />
                  <el-option value="mid" label="中等（70-89）" />
                  <el-option value="high" label="优秀（≥90）" />
                </el-select>
              </div>
            </div>
          </template>
          <el-table :data="rows" v-loading="loading" size="small">
            <el-table-column prop="ticketNo" label="工单号" width="96" />
            <el-table-column prop="username" label="客户" width="84" />
            <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
            <el-table-column label="分类" width="84">
              <template #default="s"><el-tag size="small" :type="catType(s.row.category)">{{ CAT[s.row.category] || s.row.category }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="handler" label="坐席" width="84" />
            <el-table-column label="质检分" width="80" align="center">
              <template #default="s">
                <span class="dh-num" :style="{ color: scoreColor(s.row.qcScore), fontWeight: 600 }">{{ s.row.qcScore }}</span>
              </template>
            </el-table-column>
            <el-table-column label="满意度" width="78" align="center">
              <template #default="s">
                <span v-if="s.row.satisfaction" class="dh-num">{{ s.row.satisfaction }} 星</span>
                <span v-else style="color:var(--el-text-color-placeholder)">—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="84">
              <template #default="s"><el-tag size="small" :type="stType(s.row.status)">{{ ST[s.row.status] || s.row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="64">
              <template #default="s"><el-button link type="primary" size="small" @click="open(s.row)">质检意见</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination style="margin-top:12px; justify-content:flex-end" layout="total, prev, pager, next"
                         :total="total" :page-size="f.size" v-model:current-page="f.page" @current-change="loadRows" />
        </el-card>
      </el-col>
    </el-row>

    <el-drawer v-model="drawer" :title="cur && cur.ticketNo + ' · 质检结果'" size="36%">
      <template v-if="cur">
        <div class="qc-score-ring dh-num" :style="{ color: scoreColor(cur.qcScore) }">{{ cur.qcScore }}</div>
        <div class="qc-title">{{ cur.title }}</div>
        <el-divider />
        <div class="qa-lbl">AI 质检意见</div>
        <div class="qc-problem">{{ qcProblem(cur) }}</div>
        <div class="qa-lbl" style="margin-top:16px">工单信息</div>
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="客户">{{ cur.username }}</el-descriptions-item>
          <el-descriptions-item label="坐席">{{ cur.handler || '—' }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ CAT[cur.category] || cur.category }}</el-descriptions-item>
          <el-descriptions-item label="满意度">{{ cur.satisfaction ? cur.satisfaction + ' 星' : '未评价' }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
defineOptions({ name: 'QcStatsPage' })
import { ref, computed, onMounted } from 'vue'
import api from '../../api'
import { useUserStore } from '../../stores/user'

// 工单分类字典驱动（系统管理-字典维护，登录时已随 store 加载），空时回退内置
const catStore = useUserStore()
const CAT_FALLBACK = { PRODUCT: '产品咨询', BUG: '缺陷故障', ACCOUNT: '账号权限', BILLING: '计费账单', USAGE: '使用帮助', OTHER: '其他' }
const CAT = computed(() => {
  const d = {}
  ;((catStore.dicts || {}).ticket_category || []).forEach(x => { d[x.value] = x.label })
  return Object.keys(d).length ? d : CAT_FALLBACK
})
const ST = { PENDING: '待受理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }

const stats = ref({}), rows = ref([]), total = ref(0), loading = ref(false)
const f = ref({ page: 1, size: 10, category: null, band: null })
const drawer = ref(false), cur = ref(null)

const cards = computed(() => {
  const o = stats.value.overall || {}
  const qcDone = Number(stats.value.qcDone || 0)
  const totalTk = Number(o.total || 0)
  const lowCnt = rowsAllLow.value
  return [
    { label: '已质检工单', v: qcDone },
    { label: '质检均分', v: o.qc ? Number(o.qc).toFixed(1) : '—' },
    { label: '低分工单（<70）', v: lowCnt },
    { label: '满意度均分', v: o.sat ? Number(o.sat).toFixed(2) : '—' }
  ]
})
const lowQc = computed(() => stats.value.lowQc || [])
const rowsAllLow = computed(() => lowQc.value.length)
const satRows = computed(() => {
  const dist = stats.value.satisfactionDist || []
  const byStar = {}
  dist.forEach(d => { byStar[d.k] = Number(d.v) })
  const tot = Object.values(byStar).reduce((a, b) => a + b, 0)
  return [5, 4, 3, 2, 1].map(star => ({
    star, cnt: byStar[star] || 0,
    pct: tot ? Math.round((byStar[star] || 0) / tot * 100) : 0
  }))
})
const satTotal = computed(() => satRows.value.reduce((a, b) => a + b.cnt, 0))

async function loadStats() {
  stats.value = await api.get('/v1/tickets/stats')
}
async function loadRows() {
  loading.value = true
  try {
    const minScore = f.value.band === 'low' ? undefined : (f.value.band === 'mid' ? 70 : (f.value.band === 'high' ? 90 : undefined))
    // 分数段：low = <70（用 max 不行，后端只支持 minScore；low 段用前端过滤近似）
    const d = await api.get('/v1/tickets/qc/list', {
      page: f.value.page, size: f.value.size,
      category: f.value.category || undefined,
      minScore: minScore
    })
    let recs = d.records || []
    if (f.value.band === 'low') recs = recs.filter(r => r.qcScore < 70)
    rows.value = recs
    total.value = f.value.band === 'low' ? recs.length : Number(d.total || 0)
  } finally { loading.value = false }
}
function open(row) { cur.value = row; drawer.value = true }
const qcProblem = row => {
  try {
    const j = JSON.parse(row.qcResult || '{}')
    return j.problems || '（无意见）'
  } catch (e) { return row.qcResult || '（无意见）' }
}
const scoreColor = s => s >= 90 ? '#2e7d32' : (s >= 70 ? '#b8860b' : '#c62828')
const catType = c => ({ BUG: 'danger', BILLING: 'warning', PRODUCT: 'primary', USAGE: 'success' }[c] || 'info')
const stType = s => ({ PENDING: 'info', PROCESSING: 'warning', RESOLVED: 'success', CLOSED: 'info' }[s] || 'info')

onMounted(() => { loadStats(); loadRows() })
</script>

<style scoped>
.stat { text-align: center; }
.num { font-size: 26px; font-weight: 700; }
.lbl { color: var(--el-text-color-secondary); font-size: 12.5px; margin-top: 4px; }
.sat-row { display: flex; align-items: center; gap: 10px; margin: 10px 0; }
.sat-lbl { width: 36px; font-size: 12.5px; color: var(--el-text-color-secondary); }
.sat-bar { flex: 1; height: 10px; border-radius: 5px; background: var(--el-fill-color); overflow: hidden; }
.sat-fill { height: 100%; border-radius: 5px; background: var(--el-color-primary); transition: width .4s ease; }
.sat-cnt { width: 30px; text-align: right; font-size: 12.5px; }
.qc-score-ring { font-size: 44px; font-weight: 700; text-align: center; }
.qc-title { text-align: center; color: var(--el-text-color-secondary); font-size: 13px; margin-top: 4px; }
.qa-lbl { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.qc-problem { padding: 12px 14px; border-radius: 10px; background: var(--el-fill-color-light); font-size: 13.5px; line-height: 1.8; white-space: pre-wrap; }
</style>
