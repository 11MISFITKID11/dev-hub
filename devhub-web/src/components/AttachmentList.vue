<template>
  <div>
    <el-upload :show-file-list="false" :before-upload="doUpload" action="" style="margin-bottom:10px">
      <el-button size="small" type="primary" plain :loading="uploading">{{ uploading ? '上传中…' : '＋ 上传附件' }}</el-button>
    </el-upload>
    <div v-if="!list.length && !loading" style="color:var(--el-text-color-secondary); font-size:12.5px; padding:6px 0">
      暂无附件
    </div>
    <div v-for="a in list" :key="a.id" class="att-item">
      <span class="att-icon">{{ iconOf(a.fileName) }}</span>
      <div style="flex:1; min-width:0">
        <div style="font-size:13px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap" :title="a.fileName">{{ a.fileName }}</div>
        <div style="font-size:11px; color:var(--el-text-color-secondary)">
          {{ fmtSize(a.fileSize) }} · {{ a.uploader }} · {{ (a.createTime || '').replace('T', ' ').slice(0, 16) }}
        </div>
      </div>
      <el-button link type="primary" size="small" @click="download(a)">下载</el-button>
      <el-button link type="danger" size="small" @click="del(a)">删除</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({ objType: String, objId: [String, Number] })
const list = ref([]), loading = ref(false), uploading = ref(false)

async function load() {
  if (!props.objId) return
  loading.value = true
  try { list.value = (await api.get('/attachments', { params: { objType: props.objType, objId: props.objId } })) || [] }
  finally { loading.value = false }
}
async function doUpload(file) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    await api.post(`/attachments/upload?objType=${props.objType}&objId=${props.objId}`, fd)
    load()
  } catch (e) { /* 已提示 */ } finally { uploading.value = false }
  return false
}
function download(a) {
  const token = localStorage.getItem('dh_token')
  // axios 拦截器不适合二进制流，直接开授权下载
  fetch(`/api/attachments/${a.id}/download`, { headers: { Authorization: 'Bearer ' + token } })
    .then(r => { if (!r.ok) throw new Error('下载失败'); return r.blob() })
    .then(b => {
      const url = URL.createObjectURL(b)
      const link = document.createElement('a')
      link.href = url; link.download = a.fileName
      link.click()
      URL.revokeObjectURL(url)
    })
    .catch(() => api.get('/attachments').then(() => {}))
}
async function del(a) {
  await ElMessageBox.confirm(`删除附件「${a.fileName}」？`)
  await api.delete('/attachments/' + a.id)
  load()
}
const fmtSize = n => !n ? '-' : (n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB')
const iconOf = name => {
  const ext = (name.split('.').pop() || '').toLowerCase()
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'].includes(ext)) return '🖼'
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(ext)) return '🗜'
  if (['pdf'].includes(ext)) return '📕'
  if (['doc', 'docx'].includes(ext)) return '📄'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return '📊'
  return '📎'
}
watch(() => props.objId, () => load(), { immediate: true })
</script>

<style scoped>
.att-item {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px;
  border-radius: 10px; margin-bottom: 6px;
  background: var(--dh-glass-bg); border: 1px solid var(--dh-hairline);
}
.att-icon { font-size: 16px; }
</style>
