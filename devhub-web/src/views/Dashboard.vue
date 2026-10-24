<template>
  <div>
    <el-row :gutter="14">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="never" class="stat"><div class="num">{{ c.v }}</div><div class="lbl">{{ c.label }}</div></el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" style="margin-top: 16px">
      <template #header>近 7 日缺陷趋势</template>
      <div class="trend">
        <div v-for="t in trend" :key="t.date" class="bar-wrap">
          <div class="bar" :style="{ height: (Math.max(4, t.count / maxT * 90)) + 'px' }"><span>{{ t.count }}</span></div>
          <i>{{ t.date }}</i>
        </div>
      </div>
    </el-card>
    <el-row :gutter="14" style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="never"><template #header>AI 模块概览</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="知识库文档">{{ d.aiOverview?.documents ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="AI 会话">{{ d.aiOverview?.conversations ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="开放知识缺口">{{ d.aiOverview?.gaps ?? '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never"><template #header>任务概览</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="待办 / 进行中 / 完成">{{ d.todoTasks }} / {{ d.doingTasks }} / {{ d.doneTasks }}</el-descriptions-item>
            <el-descriptions-item label="完成率">{{ d.completionRate }}%</el-descriptions-item>
            <el-descriptions-item label="我的待办">{{ d.myTodo }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
defineOptions({ name: 'Dashboard' })
import { ref, computed, onMounted } from 'vue'
import api from '../api'

const d = ref({})
const cards = computed(() => [
  { label: '活跃项目', v: d.value.activeProjects ?? '-' },
  { label: '未修复缺陷', v: d.value.openDefects ?? '-' },
  { label: '任务完成率', v: (d.value.completionRate ?? '-') + '%' },
  { label: '我的待办', v: d.value.myTodo ?? '-' }
])
const trend = computed(() => d.value.defectTrend7d || [])
const maxT = computed(() => Math.max(...trend.value.map(t => t.count), 1))

onMounted(async () => { d.value = await api.get('/report/dashboard') })
</script>

<style scoped>
.stat { text-align: center; }
.num {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -.5px;
  color: var(--el-text-color-primary);
  font-family: var(--dh-font-mono);
  font-variant-numeric: tabular-nums;
}
.lbl { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 2px; }
.trend { display: flex; align-items: flex-end; gap: 12px; height: 120px; padding-top: 20px; }
.bar-wrap { flex: 1; display: flex; flex-direction: column; align-items: center; }
.bar { width: 60%; background: rgba(255, 59, 48, 0.78); border-radius: 7px 7px 2px 2px; position: relative; }
.bar span { position: absolute; top: -18px; left: 50%; transform: translateX(-50%); font-size: 11px; color: var(--el-text-color-secondary); }
.bar-wrap i { font-style: normal; font-size: 11px; color: var(--el-text-color-secondary); margin-top: 4px; }
</style>
