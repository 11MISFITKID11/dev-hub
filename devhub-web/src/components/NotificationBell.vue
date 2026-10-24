<template>
  <el-popover placement="bottom-end" :width="380" trigger="click" popper-class="nt-pop" @show="loadList">
    <template #reference>
      <span class="nt-trigger">
        <el-badge :value="unread" :hidden="!unread" :max="99">
          <el-icon :size="19"><Bell /></el-icon>
        </el-badge>
      </span>
    </template>
    <div class="nt-head">
      <b>通知</b>
      <el-button v-if="unread" link type="primary" size="small" @click="readAll">全部已读</el-button>
    </div>
    <div class="nt-list">
      <div v-if="!list.length && !loading" class="nt-empty">
        <el-empty :image-size="60" description="暂无通知" />
      </div>
      <div v-for="n in list" :key="n.id" class="nt-item" :class="{ unread: !n.isRead }" @click="read(n)">
        <span class="nt-dot" :class="n.type"></span>
        <div class="nt-body">
          <div class="nt-title">{{ n.title }}</div>
          <div class="nt-content">{{ n.content }}</div>
          <div class="nt-time">{{ fmt(n.createTime) }}</div>
        </div>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElNotification } from 'element-plus'
import api from '../api'

const router = useRouter()
const unread = ref(0), list = ref([]), loading = ref(false)
let timer = null

async function loadUnread() {
  try { unread.value = await api.get('/notifications/unread-count') } catch (e) {}
}
async function loadList() {
  loading.value = true
  try {
    const d = await api.get('/notifications', { params: { page: 1, size: 10 } })
    list.value = d.records || []
  } finally { loading.value = false }
}
async function readAll() {
  await api.post('/notifications/read-all')
  loadUnread(); loadList()
}
async function read(n) {
  if (!n.isRead) {
    try { await api.post(`/notifications/${n.id}/read`) } catch (e) {}
    n.isRead = 1; loadUnread()
  }
  if (n.refUrl) {
    const path = n.refUrl.includes('#') ? n.refUrl.slice(n.refUrl.indexOf('#') + 1) : n.refUrl
    if (path) router.push(path)
  }
}
const fmt = t => (t || '').replace('T', ' ').slice(5, 16)

let es = null

function connectSse() {
  const token = localStorage.getItem('dh_token')
  if (!token || typeof EventSource === 'undefined') return
  try {
    es = new EventSource(`/api/notifications/stream?token=${encodeURIComponent(token)}`)
    es.addEventListener('notify', e => {
      try {
        const n = JSON.parse(e.data)
        loadUnread()
        ElNotification({ title: n.title || '新通知', message: (n.content || '').slice(0, 60), type: 'info', duration: 4000 })
      } catch (err) {}
    })
    es.onerror = () => { es && es.close(); es = null }   // 断线由下方轮询兜底，30s 后重试连接
  } catch (e) {}
}

onMounted(() => {
  loadUnread()
  connectSse()
  timer = setInterval(() => { loadUnread(); if (!es) connectSse() }, 30000)
})
onUnmounted(() => { clearInterval(timer); es && es.close() })
</script>

<style scoped>
.nt-trigger { display: inline-flex; align-items: center; cursor: pointer; color: var(--el-text-color-regular); }
.nt-head { display: flex; justify-content: space-between; align-items: center; padding: 2px 4px 8px; border-bottom: 1px solid var(--dh-hairline); }
.nt-list { max-height: 380px; overflow-y: auto; }
.nt-empty { padding: 8px 0; }
.nt-item { display: flex; gap: 10px; padding: 10px 8px; border-radius: 10px; cursor: pointer; transition: background .15s ease; }
.nt-item:hover { background: rgba(0, 113, 227, 0.06); }
.nt-item.unread { background: rgba(0, 113, 227, 0.04); }
.nt-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; background: var(--el-border-color); }
.nt-dot.mention { background: #af52de; }
.nt-dot.assign { background: var(--el-color-primary); }
.nt-dot.defect { background: #ff453a; }
.nt-dot.iteration { background: #ff9f0a; }
.nt-dot.doc_audit { background: #30d158; }
.nt-title { font-size: 13px; font-weight: 600; }
.nt-item.unread .nt-title::after { content: ''; display: inline-block; width: 6px; height: 6px; margin-left: 6px; border-radius: 50%; background: var(--el-color-primary); vertical-align: middle; }
.nt-content { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.nt-time { font-size: 11px; color: var(--el-text-color-placeholder); margin-top: 3px; }
</style>
