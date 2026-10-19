<template>
  <div style="display:grid; gap:16px">
    <el-card shadow="never">
      <template #header>待处理知识缺口 —— 拒答自动记录，一键生成 AI 草稿</template>
      <el-table :data="gaps" size="small">
        <el-table-column prop="question" label="未被回答的问题" />
        <el-table-column label="次数" width="70"><template #default="s">×{{ s.row.hitCount }}</template></el-table-column>
        <el-table-column width="110"><template #default="s">
          <el-button link type="primary" :loading="genId === s.row.id" @click="gen(s.row)">生成草稿</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>待审核文档（AI 草稿 / 冲突）</template>
      <el-table :data="pending" size="small">
        <el-table-column prop="fileName" label="文档" />
        <el-table-column label="来源" width="100"><template #default="s">{{ s.row.sourceType === 'ai_generated' ? '🤖 AI 草稿' : '上传' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s">
          <el-tag :type="s.row.lifeStatus === 'draft' ? 'warning' : 'danger'" size="small">{{ s.row.lifeStatus === 'draft' ? '草稿' : '冲突' }}</el-tag>
        </template></el-table-column>
        <el-table-column prop="chunkCount" label="切片" width="70" />
        <el-table-column width="130"><template #default="s">
          <el-button link type="success" @click="review(s.row, true)">采纳</el-button>
          <el-button link type="danger" @click="review(s.row, false)">拒绝</el-button>
        </template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>已停用文档（踩率高 / 过期 / 旧版）</template>
      <el-table :data="disabled" size="small">
        <el-table-column prop="fileName" label="文档" />
        <el-table-column prop="hitCount" label="命中" width="80" />
        <el-table-column label="状态" width="90"><template #default="s"><el-tag type="info" size="small">已停用</el-tag></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
defineOptions({ name: 'OpsPage' })
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const gaps = ref([]), pending = ref([]), disabled = ref([]), genId = ref(null)

async function load() {
  try { gaps.value = (await api.get('/v1/knowledge/gaps', { params: { status: 'open' } })) || [] } catch (e) { gaps.value = [] }
  try {
    const d = await api.get('/v1/documents', { params: { page: 1, size: 50 } })
    const rs = d.records || []
    pending.value = rs.filter(r => r.lifeStatus === 'draft' || r.lifeStatus === 'conflict')
    disabled.value = rs.filter(r => ['disabled', 'retired', 'superseded'].includes(r.lifeStatus)).slice(0, 8)
  } catch (e) {}
}
async function gen(g) {
  genId.value = g.id
  try { ElMessage.success(await api.post(`/v1/knowledge/gaps/${g.id}/generate`)); load() }
  finally { genId.value = null }
}
async function review(r, ok) {
  await api.post(`/v1/knowledge/${r.id}/review?approve=${ok}`)
  ElMessage.success(ok ? '已采纳为正式知识' : '已拒绝'); load()
}
onMounted(load)
</script>
