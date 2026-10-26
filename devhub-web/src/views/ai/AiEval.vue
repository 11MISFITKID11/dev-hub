<template>
  <div style="display:grid; gap:16px">
    <el-card shadow="never">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center">
          <span>评测用例 —— 改提示词/换模型/调检索参数后跑一轮，指标不回退才算改完</span>
          <el-button type="primary" size="small" @click="openEdit()">＋ 新增用例</el-button>
        </div>
      </template>
      <el-table :data="cases" size="small">
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="expectDocName" label="期望命中文档" width="170" show-overflow-tooltip />
        <el-table-column prop="expectKeywords" label="答案须含关键词" width="150" show-overflow-tooltip />
        <el-table-column label="启用" width="70"><template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" @change="v => toggleCase(row, v)" />
        </template></el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link size="small" type="danger" @click="delCase(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center">
          <span>跑批与回归对比</span>
          <div style="display:flex; gap:10px; align-items:center">
            <el-radio-group v-model="mode" size="small">
              <el-radio-button value="retrieval">检索模式（免费）</el-radio-button>
              <el-radio-button value="full">完整模式（含忠实度）</el-radio-button>
            </el-radio-group>
            <el-button type="primary" size="small" :loading="running" @click="runEval">跑一轮</el-button>
          </div>
        </div>
      </template>
      <el-table :data="runs" size="small" @row-click="showResults" style="cursor:pointer">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column prop="createTime" label="时间" width="160">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="mode" label="模式" width="90" />
        <el-table-column label="命中率" width="100"><template #default="{ row }">{{ row.hitCount }}/{{ row.total }}（{{ pct(row.hitCount, row.total) }}）</template></el-table-column>
        <el-table-column label="门控通过" width="100"><template #default="{ row }">{{ row.gatePass }}/{{ row.total }}（{{ pct(row.gatePass, row.total) }}）</template></el-table-column>
        <el-table-column prop="avgTop1" label="平均top1" width="80" />
        <el-table-column label="MRR" width="70"><template #default="{ row }">{{ row.avgMrr ?? '-' }}</template></el-table-column>
        <el-table-column label="NDCG" width="70"><template #default="{ row }">{{ row.avgNdcg ?? '-' }}</template></el-table-column>
        <el-table-column label="忠实度" width="80"><template #default="{ row }">{{ row.avgFaith ?? '-' }}</template></el-table-column>
        <el-table-column prop="costMs" label="耗时" width="90"><template #default="{ row }">{{ (row.costMs / 1000).toFixed(1) }}s</template></el-table-column>
        <el-table-column prop="createdBy" label="执行人" width="90" />
      </el-table>
      <div v-if="detail.length" style="margin-top:14px">
        <b style="font-size:13px">最近一次明细（点击上方任意一行切换）</b>
        <el-table :data="detail" size="small" style="margin-top:8px">
          <el-table-column label="命中" width="60"><template #default="{ row }">
            <el-tag :type="row.hit === 1 ? 'success' : 'danger'" size="small">{{ row.hit === 1 ? '✓' : '✗' }}</el-tag>
          </template></el-table-column>
          <el-table-column label="门控" width="60"><template #default="{ row }">
            <el-tag :type="row.gatePass === 1 ? 'success' : 'warning'" size="small">{{ row.gatePass === 1 ? '过' : '拒答' }}</el-tag>
          </template></el-table-column>
          <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="top1Score" label="top1" width="70" />
          <el-table-column prop="mrr" label="MRR" width="64"><template #default="{ row }">{{ row.mrr ?? '-' }}</template></el-table-column>
          <el-table-column prop="faithScore" label="忠实度" width="70"><template #default="{ row }">{{ row.faithScore ?? '-' }}</template></el-table-column>
          <el-table-column prop="topDocs" label="重排 Top5 文档" min-width="240" show-overflow-tooltip />
        </el-table>
      </div>
    </el-card>

    <el-dialog v-model="dlg" :title="form.id ? '编辑用例' : '新增用例'" width="520px">
      <el-form label-width="110px">
        <el-form-item label="问题"><el-input v-model="form.question" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="期望命中文档">
          <el-input v-model="form.expectDocName" placeholder="如：阿里巴巴Java开发手册（包含匹配，留空则只看门控）" />
        </el-form-item>
        <el-form-item label="答案须含关键词">
          <el-input v-model="form.expectKeywords" placeholder="逗号分隔，完整模式校验，如：接口,Impl" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
defineOptions({ name: 'AiEvalPage' })
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const cases = ref([]), runs = ref([]), detail = ref([])
const dlg = ref(false), form = ref({}), mode = ref('retrieval'), running = ref(false)
const pct = (a, b) => b ? Math.round(a * 100 / b) + '%' : '-'

async function loadCases() {
  cases.value = (await api.get('/v1/eval/cases', { params: { size: 50 } })).records || []
}
async function loadRuns() {
  runs.value = (await api.get('/v1/eval/runs')) || []
}
function openEdit(row) { form.value = row ? { ...row } : {}; dlg.value = true }
async function save() {
  if (form.value.id) await api.put(`/v1/eval/cases/${form.value.id}`, form.value)
  else await api.post('/v1/eval/cases', form.value)
  ElMessage.success('已保存'); dlg.value = false; loadCases()
}
async function toggleCase(row, v) {
  await api.put(`/v1/eval/cases/${row.id}`, { enabled: v ? 1 : 0 })
  row.enabled = v ? 1 : 0
}
async function delCase(row) {
  await ElMessageBox.confirm(`删除用例「${row.question.slice(0, 30)}…」？`, '确认')
  await api.delete(`/v1/eval/cases/${row.id}`); loadCases()
}
async function runEval() {
  running.value = true
  try {
    const r = await api.post(`/v1/eval/run?mode=${mode.value}`)
    ElMessage.success(`跑批完成：命中 ${r.hitCount}/${r.total}，耗时 ${(r.costMs / 1000).toFixed(1)}s`)
    await loadRuns()
    detail.value = (await api.get(`/v1/eval/runs/${r.id}/results`)) || []
  } finally { running.value = false }
}
async function showResults(row) {
  detail.value = (await api.get(`/v1/eval/runs/${row.id}/results`)) || []
}
onMounted(() => { loadCases(); loadRuns() })
</script>
