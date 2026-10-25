<template>
  <div>
    <div class="table-toolbar">
      <span style="font-size:13px;color:var(--el-text-color-secondary)">
        AI 答不了的问题会在这里跟进：坐席处理后可评价，推动知识库补全
      </span>
      <span style="flex:1"></span>
      <el-button type="primary" @click="dlg = true; form = { title: '', content: '' }">新建工单</el-button>
    </div>

    <el-table :data="rows" v-loading="loading">
      <el-table-column prop="ticketNo" label="工单号" width="100" />
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="分类" width="100"><template #default="s">{{ CAT[s.row.category] || '未分类' }}</template></el-table-column>
      <el-table-column label="优先级" width="80"><template #default="s">
        <el-tag size="small" :type="PRI_STYLE[s.row.priority]" effect="plain">{{ PRI[s.row.priority] || '-' }}</el-tag>
      </template></el-table-column>
      <el-table-column label="来源" width="96"><template #default="s">
        <span style="font-size:12px">{{ SRC[s.row.source] || s.row.source }}</span>
      </template></el-table-column>
      <el-table-column label="状态" width="88"><template #default="s">
        <el-tag size="small" :type="STATUS_STYLE[s.row.status]">{{ STATUS[s.row.status] }}</el-tag>
      </template></el-table-column>
      <el-table-column label="满意度" width="86"><template #default="s">
        <span v-if="s.row.satisfaction" style="color:#f7ba2a">{{ '★'.repeat(s.row.satisfaction) }}</span>
        <el-button v-else-if="s.row.status === 'RESOLVED' || s.row.status === 'CLOSED'" link type="primary" size="small" @click="open(s.row.id)">去评价</el-button>
        <span v-else style="color:var(--el-text-color-placeholder)">-</span>
      </template></el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column label="操作" width="80" fixed="right"><template #default="s">
        <el-button link type="primary" size="small" @click="open(s.row.id)">详情</el-button>
      </template></el-table-column>
    </el-table>
    <div class="pager"><el-pagination background layout="total, prev, pager, next" :total="total"
      :page-size="q.size" :current-page="q.page" @current-change="p => { q.page = p; load() }" /></div>

    <el-dialog v-model="dlg" title="新建工单" width="520px">
      <el-form label-width="60px">
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="100" placeholder="一句话描述问题" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="6"
          placeholder="问题详情：做了什么、期望什么、实际发生什么" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTk">提交（AI 将自动分类定级）</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" size="44%" :title="cur && cur.ticketNo + ' · ' + cur.title">
      <template v-if="cur">
        <div class="blk"><div class="blk-t">工单内容</div><div class="blk-b">{{ cur.content }}</div></div>
        <div class="blk"><div class="blk-t">处理进展</div>
          <el-timeline style="padding-left:2px; margin-top:8px">
            <el-timeline-item v-for="r in replies" :key="r.id" :timestamp="r.createTime"
              :type="r.role === 'agent' ? 'primary' : 'info'">
              <b>{{ r.role === 'agent' ? '坐席 ' + r.author : '我' }}：</b><span style="white-space:pre-wrap">{{ r.content }}</span>
            </el-timeline-item>
            <el-timeline-item v-if="!replies.length" timestamp="等待坐席受理">已提交，等待受理</el-timeline-item>
          </el-timeline>
        </div>

        <!-- 补充信息 -->
        <div v-if="cur.status === 'PENDING' || cur.status === 'PROCESSING'" style="border-top:1px solid var(--dh-hairline); padding-top:12px">
          <el-input v-model="replyText" type="textarea" :rows="3" placeholder="补充信息（截图说明、复现步骤等）" />
          <el-button type="primary" style="margin-top:8px" @click="addReply">提交补充</el-button>
        </div>

        <!-- 满意度评价 -->
        <div v-else-if="!cur.satisfaction" style="border-top:1px solid var(--dh-hairline); padding-top:12px">
          <div style="font-size:13px; margin-bottom:8px">问题已解决，请评价本次服务：</div>
          <el-rate v-model="stars" show-text style="margin-bottom:8px" />
          <el-input v-model="comment" placeholder="评价（可选）" />
          <el-button type="primary" style="margin-top:8px" @click="rate">提交评价</el-button>
        </div>
        <div v-else style="border-top:1px solid var(--dh-hairline); padding-top:12px; font-size:13px">
          已评价：<span style="color:#f7ba2a">{{ '★'.repeat(cur.satisfaction) }}</span>
          <span style="margin-left:8px;color:var(--el-text-color-secondary)">{{ cur.satisfactionComment }}</span>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const STATUS = { PENDING: '待受理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' }
const STATUS_STYLE = { PENDING: 'warning', PROCESSING: 'primary', RESOLVED: 'success', CLOSED: 'info' }
const CAT = { PRODUCT: '产品咨询', BUG: '缺陷故障', ACCOUNT: '账号权限', BILLING: '计费相关', USAGE: '使用帮助', OTHER: '其他' }
const PRI = { HIGH: '高', MEDIUM: '中', LOW: '低' }
const PRI_STYLE = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const SRC = { manual: '手动创建', chat_refusal: 'AI 拒答转单', chat_dislike: '点踩转单' }

const q = ref({ page: 1, size: 10 })
const rows = ref([]), total = ref(0), loading = ref(false)
const dlg = ref(false), form = ref({}), creating = ref(false)
const drawer = ref(false), cur = ref(null), replies = ref([])
const replyText = ref(''), stars = ref(5), comment = ref('')

async function load() {
  loading.value = true
  try {
    const d = await api.get('/v1/tickets/mine', { params: q.value })
    rows.value = d.records || []; total.value = Number(d.total || 0)
  } finally { loading.value = false }
}
async function open(id) {
  const d = await api.get(`/v1/tickets/${id}`)
  cur.value = d.ticket; replies.value = d.replies || []
  stars.value = 5; comment.value = ''
  drawer.value = true
}
async function createTk() {
  if (!form.value.title.trim() || !form.value.content.trim()) return ElMessage.warning('请填写标题与内容')
  creating.value = true
  try {
    await api.post('/v1/tickets', { ...form.value, source: 'manual' })
    ElMessage.success('已提交，AI 分类中，可在列表查看')
    dlg.value = false; load()
  } finally { creating.value = false }
}
async function addReply() {
  if (!replyText.value.trim()) return
  await api.post(`/v1/tickets/${cur.value.id}/reply`, { content: replyText.value })
  ElMessage.success('已补充'); replyText.value = ''
  open(cur.value.id)
}
async function rate() {
  await api.post(`/v1/tickets/${cur.value.id}/rate`, { stars: stars.value, comment: comment.value })
  ElMessage.success('感谢评价'); open(cur.value.id); load()
}
onMounted(load)
</script>

<style scoped>
.blk { margin-bottom: 14px; }
.blk-t { font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.blk-b { font-size: 13px; line-height: 1.7; white-space: pre-wrap; background: var(--el-fill-color-light); border-radius: 8px; padding: 10px 12px; }
</style>
