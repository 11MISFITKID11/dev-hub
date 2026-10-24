<template>
  <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
    <el-card shadow="never" style="grid-column:1/-1"><template #header>问答质量 —— 反馈 / 忠实度 / 知识缺口（数据落库即运营）</template>
      <div style="display:grid; grid-template-columns:1fr 1fr 1.4fr; gap:20px">
        <div>
          <div style="font-size:12px; color:var(--el-text-color-secondary); margin-bottom:6px">用户反馈</div>
          <div style="font-size:22px"><b style="color:#67c23a">👍 {{ qa.feedbackUp || 0 }}</b>
            <span style="margin:0 10px; color:var(--el-border-color)">/</span>
            <b style="color:#f56c6c">👎 {{ qa.feedbackDown || 0 }}</b></div>
          <div style="font-size:11px; color:var(--el-text-color-secondary)">点踩自动记入知识缺口</div>
        </div>
        <div>
          <div style="font-size:12px; color:var(--el-text-color-secondary); margin-bottom:6px">忠实度（近30天 {{ qa.faithCount || 0 }} 次）</div>
          <div style="font-size:22px"><b>{{ (qa.faithAvg || 0).toFixed(2) }}</b></div>
          <div style="font-size:11px" :style="{ color: (qa.faithLow || 0) > 0 ? '#e6a23c' : 'var(--el-text-color-secondary)' }">
            低于 0.6 共 {{ qa.faithLow || 0 }} 次{{ (qa.faithLow || 0) > 0 ? '，建议复核' : '' }}
          </div>
        </div>
        <div>
          <div style="font-size:12px; color:var(--el-text-color-secondary); margin-bottom:6px">待处理知识缺口 Top5</div>
          <div v-if="qa.gaps && qa.gaps.length" style="display:grid; gap:4px">
            <div v-for="g in qa.gaps" :key="g.question" style="font-size:12.5px; display:flex; justify-content:space-between; gap:10px">
              <span style="overflow:hidden; text-overflow:ellipsis; white-space:nowrap">{{ g.question }}</span>
              <el-tag size="small" type="warning">×{{ g.hitCount }}</el-tag>
            </div>
          </div>
          <div v-else style="font-size:12.5px; color:var(--el-text-color-secondary)">暂无缺口，知识覆盖良好</div>
        </div>
      </div>
    </el-card>
    <el-card shadow="never"><template #header>周期时长与吞吐（Cycle Time · Swarmia 式）</template>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="已完成任务数">{{ flow.doneCount }}</el-descriptions-item>
        <el-descriptions-item label="周期时长 P50">{{ fmt(flow.cycleTimeHoursP50) }}</el-descriptions-item>
        <el-descriptions-item label="周期时长 P90">{{ fmt(flow.cycleTimeHoursP90) }}</el-descriptions-item>
        <el-descriptions-item label="当前 WIP（进行中）">{{ flow.currentWip }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-card shadow="never"><template #header>周吞吐量</template>
      <div style="display:flex; align-items:flex-end; gap:8px; height:140px">
        <div v-for="(c, w) in flow.throughputByWeek || {}" :key="w" style="flex:1; text-align:center">
          <div :style="{ height: (c / maxWeek * 116 + 4) + 'px', background: 'var(--el-color-primary)', borderRadius: '6px 6px 0 0', margin: '0 auto', width: '60%' }"></div>
          <b style="font-size:12px">{{ c }}</b>
          <div style="font-size:10px; color:var(--el-text-color-secondary)">{{ w }}</div>
        </div>
      </div>
    </el-card>
    <el-card shadow="never" style="grid-column:1/-1"><template #header>累积流图 CFD（近 14 天三列存量）</template>
      <div style="display:grid; grid-template-columns:repeat(14, 1fr); gap:3px; align-items:end; height:150px">
        <div v-for="p in flow.cfd || []" :key="p.date" style="display:flex; flex-direction:column; justify-content:flex-end; height:100%">
          <div :style="{ background:'#67c23a', height:(p.done/cfdScale)+'px', borderRadius:'2px' }" :title="'DONE '+p.done"></div>
          <div :style="{ background:'#e6a23c', height:(p.doing/cfdScale)+'px' }" :title="'DOING '+p.doing"></div>
          <div :style="{ background:'#909399', height:(p.todo/cfdScale)+'px', borderRadius:'0 0 2px 2px' }" :title="'TODO '+p.todo"></div>
          <i style="font-style:normal; font-size:9px; color:var(--el-text-color-secondary); text-align:center; margin-top:2px">{{ p.date.slice(3) }}</i>
        </div>
      </div>
      <div style="font-size:11px; color:var(--el-text-color-secondary); margin-top:8px">
        灰=待办 · 橙=进行中(WIP) · 绿=已完成 —— 橙色带变宽即 WIP 膨胀预警
      </div>
    </el-card>
    <el-card shadow="never" style="grid-column:1/-1">
      <template #header>成员负载（未结任务 × 本周工时）</template>
      <el-table :data="workload" size="small" v-loading="wlLoading">
        <el-table-column label="成员" width="140"><template #default="s">{{ s.row.username }}</template></el-table-column>
        <el-table-column label="未结任务" width="110"><template #default="s">{{ s.row.openTasks }}</template></el-table-column>
        <el-table-column label="其中进行中" width="110"><template #default="s">{{ s.row.doing }}</template></el-table-column>
        <el-table-column label="本周工时">
          <template #default="s">
            <div style="display:flex; align-items:center; gap:10px">
              <div style="flex:1; max-width:280px; height:8px; border-radius:4px; background:var(--el-fill-color-light)">
                <div :style="{ width: Math.min(100, s.row.hoursThisWeek / 40 * 100) + '%', height: '8px', borderRadius: '4px', background: 'linear-gradient(90deg,var(--el-color-primary),#64d2ff)' }"></div>
              </div>
              <b style="font-size:12.5px">{{ s.row.hoursThisWeek }}h</b>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '../api'

defineOptions({ name: 'MetricsPage' })

const flow = ref({}), qa = ref({})
const workload = ref([]), wlLoading = ref(false)
const fmt = h => h == null ? '-' : (h >= 24 ? (h / 24).toFixed(1) + ' 天' : h + ' 小时')
// 图表高度按数据最大值归一化，杜绝固定像素比例导致的溢出/压扁
const maxWeek = computed(() => Math.max(...Object.values(flow.value.throughputByWeek || { x: 1 }), 1))
const cfdScale = computed(() => {
  const pts = flow.value.cfd || []
  const maxTotal = Math.max(...pts.map(p => (p.todo || 0) + (p.doing || 0) + (p.done || 0)), 1)
  return maxTotal / 140
})
onMounted(async () => {
  flow.value = await api.get('/report/flow', { params: { weeks: 8 } })
  try { qa.value = await api.get('/report/qa-quality') } catch (e) { qa.value = {} }
  wlLoading.value = true
  try { workload.value = (await api.get('/report/workload')) || [] } finally { wlLoading.value = false }
})
</script>
