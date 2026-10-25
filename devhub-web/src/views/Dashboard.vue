<template>
  <div v-loading="loading">
    <!-- 工单大盘 -->
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col :span="6" v-for="c in cards" :key="c.label"><el-card shadow="never">
        <div class="stat">
          <div class="lbl">{{ c.label }}</div>
          <div class="num dh-num" :style="c.danger && c.value > 0 ? 'color:var(--el-color-danger)' : ''">{{ c.value }}</div>
          <div class="sub">{{ c.sub }}</div>
        </div>
      </el-card></el-col>
    </el-row>

    <el-row :gutter="12">
      <!-- 问答质量 -->
      <el-col :span="12"><el-card shadow="never" header="AI 问答质量（30 天）">
        <template v-if="qa">
          <div class="qa-row"><span>👍 有用反馈</span><b class="dh-num">{{ qa.feedbackUp }}</b></div>
          <div class="qa-row"><span>👎 无用反馈（转知识缺口）</span><b class="dh-num">{{ qa.feedbackDown }}</b></div>
          <div class="qa-row"><span>忠实度均值</span><b class="dh-num">{{ (qa.faithAvg || 0).toFixed(2) }}</b></div>
          <div class="qa-row"><span>低忠实度答案（&lt;0.6）</span><b class="dh-num">{{ qa.faithLow }}</b></div>
          <div v-if="(qa.gaps || []).length" style="margin-top:10px; border-top:1px solid var(--dh-hairline); padding-top:10px">
            <div class="lbl" style="margin-bottom:6px">知识缺口 Top{{ qa.gaps.length }}（待补文档 / 已可转工单）</div>
            <div v-for="g in qa.gaps" :key="g.question" class="gap-row">
              <span class="gap-q">{{ g.question }}</span>
              <el-tag size="small" type="warning" effect="plain">{{ g.hitCount }} 次</el-tag>
            </div>
          </div>
        </template>
      </el-card></el-col>

      <!-- 工单分布与趋势 -->
      <el-col :span="12"><el-card shadow="never" header="工单分布与 7 日趋势">
        <div class="dist-row" v-for="d in catRows" :key="d.name">
          <span style="width:72px; flex-shrink:0">{{ d.name }}</span>
          <div class="bar-track"><div class="bar-fill" :style="{ width: d.pct + '%' }"></div></div>
          <span class="dh-num" style="width:28px; text-align:right">{{ d.v }}</span>
        </div>
        <div v-if="!catRows.length" class="lbl">暂无工单</div>
        <div v-if="trend.length" style="margin-top:14px; display:flex; align-items:flex-end; gap:8px; height:90px">
          <div v-for="t in trend" :key="t.d" class="trend-col" :title="`${t.d}：${t.created} 单`">
            <span class="dh-num trend-v">{{ t.created }}</span>
            <div class="trend-bar" :style="{ height: Math.max(6, t.created / maxTrend * 60) + 'px' }"></div>
            <span class="trend-d">{{ t.d }}</span>
          </div>
        </div>
      </el-card></el-col>
    </el-row>

    <el-card shadow="never" header="服务闭环说明" style="margin-top:12px">
      <el-steps :active="4" align-center style="margin-top:4px">
        <el-step title="AI 自动答" description="RAG 多路检索 + 引用溯源，命中缓存 0.3s 直返" />
        <el-step title="答不了转工单" description="CRAG 拒答 / 点踩一键转单，AI 分类定级" />
        <el-step title="人工处理" description="坐席在 AI 回复建议（同源检索）辅助下跟单" />
        <el-step title="AI 质检" description="结单自动评分，低分进看板，可重检" />
        <el-step title="满意度" description="客户评价沉淀，缺口反哺知识库" />
      </el-steps>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'

const loading = ref(false)
const stats = ref(null)
const qa = ref(null)

const CAT_NAMES = { PRODUCT: '产品咨询', BUG: '缺陷故障', ACCOUNT: '账号权限', BILLING: '计费相关', USAGE: '使用帮助', OTHER: '其他' }

const cnt = k => { const it = ((stats.value && stats.value.byStatus) || []).find(x => x.k === k); return it ? it.v : 0 }
const cards = computed(() => stats.value ? [
  { label: '待受理工单', value: cnt('PENDING'), sub: 'AI 已分类定级，等待坐席', danger: true },
  { label: '处理中', value: cnt('PROCESSING'), sub: '坐席跟进（可调 AI 建议）', danger: false },
  { label: '平均满意度', value: ((stats.value.overall.sat || 0)).toFixed(1) + ' / 5', sub: '已评价工单均值', danger: false },
  { label: 'AI 质检均分', value: Math.round(stats.value.overall.qc || 0) + ' / 100', sub: '低于 70 进入质量看板', danger: false }
] : [])

const catRows = computed(() => {
  const raw = (stats.value && stats.value.byCategory) || []
  const total = raw.reduce((s, x) => s + Number(x.v), 0) || 1
  return raw.map(x => ({ name: CAT_NAMES[x.k] || x.k, v: Number(x.v), pct: Math.round(x.v / total * 100) }))
    .sort((a, b) => b.v - a.v)
})
const trend = computed(() => (stats.value && stats.value.trend7d) || [])
const maxTrend = computed(() => Math.max(1, ...trend.value.map(t => Number(t.created))))

onMounted(async () => {
  loading.value = true
  try {
    const [s, q] = await Promise.all([
      api.get('/v1/tickets/stats').catch(() => null),
      api.get('/report/qa-quality').catch(() => null)
    ])
    stats.value = s; qa.value = q
  } finally { loading.value = false }
})
</script>

<style scoped>
.stat { text-align: center; }
.lbl { color: var(--el-text-color-secondary); font-size: 12px; }
.sub { color: var(--el-text-color-placeholder); font-size: 11px; margin-top: 4px; }
.num { font-size: 28px; font-weight: 700; letter-spacing: -.5px; margin-top: 2px; }
.qa-row { display: flex; justify-content: space-between; padding: 7px 0; font-size: 13px; border-bottom: 1px dashed var(--dh-hairline); }
.qa-row:last-of-type { border-bottom: none; }
.gap-row { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 4px 0; font-size: 12.5px; }
.gap-q { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dist-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12.5px; }
.bar-track { flex: 1; height: 8px; border-radius: 4px; background: var(--el-fill-color-light); overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; background: var(--el-color-primary); }
.trend-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 3px; }
.trend-bar { width: 60%; background: rgba(255, 159, 10, 0.75); border-radius: 4px 4px 2px 2px; }
.trend-v { font-size: 11px; color: var(--el-text-color-secondary); }
.trend-d { font-size: 10.5px; color: var(--el-text-color-placeholder); }
</style>
