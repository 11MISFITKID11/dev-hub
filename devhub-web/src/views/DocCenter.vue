<template>
  <div>
    <el-tabs v-model="tab">
      <el-tab-pane label="文档管理" name="docs">
    <el-card shadow="never">
      <template #header>文档中心 —— 发布后自动进入 AI 知识库（解析 → 切片 → 向量化）</template>
      <div class="table-toolbar">
        <el-select v-model="f.categoryId" placeholder="分类：全部" clearable style="width:140px" @change="reset">
          <el-option v-for="c in cats" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="f.auditStatus" placeholder="审核：全部" clearable style="width:130px" @change="reset">
          <el-option label="待审核" value="PENDING" /><el-option label="已发布" value="PUBLISHED" /><el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-upload :show-file-list="false" :before-upload="upload" action="">
          <el-button type="primary" :loading="uploading">{{ uploading ? '提交中…' : '上传文档' }}</el-button>
        </el-upload>
        <el-input v-model="urlIngest" placeholder="粘贴网页 URL 一键入库" style="width:280px; margin-left:10px" size="small" clearable @keydown.enter="ingestUrl">
          <template #append><el-button :loading="urlLoading" :disabled="!urlIngest || !urlIngest.startsWith('http')" @click="ingestUrl">网页入库</el-button></template>
        </el-input>
        <el-button @click="catDlg = true; catForm = {}">新建分类</el-button>
      </div>
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="fileName" label="文件" min-width="180" show-overflow-tooltip />
        <el-table-column label="分类" width="100"><template #default="s">{{ (cats.find(c => c.id === s.row.categoryId) || {}).name || '-' }}</template></el-table-column>
        <el-table-column label="解析状态" width="90"><template #default="s">
          <el-tag :type="s.row.status === 'processing' ? 'warning' : (s.row.status === 'completed' ? 'success' : 'danger')" size="small">
            {{ s.row.status === 'processing' ? '解析中' : (s.row.status === 'completed' ? '已生效' : '失败') }}</el-tag>
        </template></el-table-column>
        <el-table-column prop="chunkCount" label="切片数" width="80" />
        <el-table-column prop="uploader" label="上传人" width="90" />
        <el-table-column label="审核" width="90"><template #default="s">
          <el-tag :type="s.row.auditStatus === 'PUBLISHED' ? 'success' : (s.row.auditStatus === 'PENDING' ? 'warning' : 'danger')" size="small">
            {{ {PUBLISHED:'已发布',PENDING:'待审核',REJECTED:'已驳回'}[s.row.auditStatus] || '-' }}</el-tag>
        </template></el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="s">
            <el-button v-if="s.row.auditStatus === 'PENDING'" link type="success" @click="audit(s.row, true)">发布</el-button>
            <el-button v-if="s.row.auditStatus === 'PENDING'" link type="danger" @click="audit(s.row, false)">驳回</el-button>
            <el-button link type="primary" :loading="ridx === s.row.id" @click="reindex(s.row)">重建索引</el-button>
            <el-button link type="danger" @click="del(s.row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无文档">
            <el-button type="primary" @click="pickUpload">上传第一份文档</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :total="total" v-model:current-page="page" v-model:page-size="size" @current-change="load" @size-change="reset" />
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="知识片段" name="chunks">
        <el-card shadow="never">
          <template #header>知识片段 —— 向量化后的最小检索单元，内容命中即可被 AI 引用</template>
          <div class="table-toolbar">
            <el-input v-model="ck.kw" placeholder="搜索片段内容 / 文档名" clearable style="width:260px" @keyup.enter="ckReset" />
            <el-button type="primary" @click="ckReset">查询</el-button>
          </div>
          <el-table :data="ckRows" v-loading="ckLoading" size="small">
            <el-table-column prop="fileName" label="所属文档" min-width="160" show-overflow-tooltip />
            <el-table-column label="序号" width="70" align="center"><template #default="s">#{{ s.row.chunkIndex }}</template></el-table-column>
            <el-table-column label="内容" min-width="320" show-overflow-tooltip>
              <template #default="s">{{ (s.row.content || '').replace(/\s+/g, ' ').slice(0, 140) }}</template>
            </el-table-column>
            <el-table-column label="生命周期" width="86" align="center">
              <template #default="s">
                <el-tag size="small" :type="s.row.docStatus === 'active' ? 'success' : (s.row.docStatus === 'draft' ? 'warning' : 'info')">
                  {{ { active: '生效', draft: '草稿', archived: '归档' }[s.row.docStatus] || s.row.docStatus || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="层级" width="66" align="center">
              <template #default="s">{{ s.row.chunkType === 1 ? '父块' : (s.row.chunkType === 0 ? '子块' : '—') }}</template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="s"><el-button link type="primary" @click="ckOpen = s.row; ckDrawer = true">全文</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" background layout="total, prev, pager, next" :total="ckTotal"
                         v-model:current-page="ck.page" v-model:page-size="ck.size" @current-change="loadChunks" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="ckDrawer" :title="ckOpen && ckOpen.fileName" size="38%">
      <div style="font-size:13px; line-height:1.9; white-space:pre-wrap">{{ ckOpen && ckOpen.content }}</div>
    </el-drawer>

    <el-dialog v-model="catDlg" title="新建分类" width="360">
      <el-form ref="catRef" label-width="70px" :rules="{ name: [{ required: true, message: '请输入分类名', trigger: 'blur' }] }" :model="catForm">
        <el-form-item label="分类名" prop="name"><el-input v-model="catForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="catForm.sort" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="catDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="addCat">确定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useCrudList } from '../composables/useCrudList'

defineOptions({ name: 'DocCenter' })

const f = ref({})
const { rows, total, page, size, loading, load, reset } = useCrudList('/doc/documents', () => f.value)
const tab = ref('docs')

/* 知识片段视图（chunk 级检索单元） */
const ck = ref({ page: 1, size: 10, kw: '' })
const ckRows = ref([]), ckTotal = ref(0), ckLoading = ref(false)
const ckDrawer = ref(false), ckOpen = ref(null)
async function loadChunks() {
  ckLoading.value = true
  try {
    const d = await api.get('/v1/knowledge/chunks', { page: ck.value.page, size: ck.value.size, keyword: ck.value.kw || undefined })
    ckRows.value = d.records || []
    ckTotal.value = Number(d.total || 0)
  } finally { ckLoading.value = false }
}
function ckReset() { ck.value.page = 1; loadChunks() }
const cats = ref([]), catDlg = ref(false), catForm = ref({}), catRef = ref(null), saving = ref(false)
const uploading = ref(false), ridx = ref(null)
const urlIngest = ref(''), urlLoading = ref(false)
async function ingestUrl() {
  if (!urlIngest.value || !urlIngest.value.startsWith('http')) return
  urlLoading.value = true
  try {
    const r = await api.post('/v1/documents/url', { url: urlIngest.value })
    ElMessage.success(`「${r.fileName}」已提交解析`)
    urlIngest.value = ''
    load()
  } finally { urlLoading.value = false }
}

// 空态引导：触发上传按钮的文件选择框
function pickUpload() { document.querySelector('.el-upload input[type=file]')?.click() }

async function upload(file) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    if (f.value.categoryId) fd.append('categoryId', f.value.categoryId)
    await api.post('/v1/documents/upload' + (f.value.categoryId ? '?categoryId=' + f.value.categoryId : ''), fd)
    ElMessage.success('已提交异步解析，稍后刷新查看切片数')
    load(); poll()
  } catch (e) { /* 已提示 */ } finally { uploading.value = false }
  return false
}
function poll() {
  let n = 0
  const t = setInterval(async () => {
    n++; await load()
    if (n > 18 || !(rows.value || []).some(r => r.status === 'processing')) clearInterval(t)
  }, 5000)
}
async function audit(r, pass) {
  await api.post(`/doc/documents/${r.id}/audit?pass=${pass}`)
  ElMessage.success(pass ? '已发布' : '已驳回'); load()
}
async function reindex(r) {
  ridx.value = r.id
  try { ElMessage.success(await api.post(`/doc/documents/${r.id}/reindex`)) } finally { ridx.value = null; load() }
}
async function del(r) {
  await ElMessageBox.confirm(`删除「${r.fileName}」及其全部向量？`)
  await api.delete('/v1/documents/' + r.id); load()
}
async function addCat() {
  await catRef.value.validate()
  saving.value = true
  try {
    await api.post('/doc/categories', catForm.value)
    catDlg.value = false; cats.value = await api.get('/doc/categories')
  } finally { saving.value = false }
}

onMounted(async () => {
  load(); cats.value = await api.get('/doc/categories'); loadChunks()
})
</script>
