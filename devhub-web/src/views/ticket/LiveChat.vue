<template>
  <div class="page">
    <div class="live-wrap">
      <!-- 左：会话列表 -->
      <div class="live-list">
        <div class="live-list-head">
          <b>实时会话</b>
          <el-radio-group v-model="tab" size="small" @change="load">
            <el-radio-button value="PENDING">待接管({{ countBy('PENDING') }})</el-radio-button>
            <el-radio-button value="ACTIVE">接管中({{ countBy('ACTIVE') }})</el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
        </div>
        <div v-if="!filteredList.length" class="live-empty">暂无{{ tab === 'ALL' ? '' : (tab === 'PENDING' ? '待接管' : '接管中') }}会话</div>
        <div v-for="c in filteredList" :key="c.id" class="live-item" :class="{ sel: sel && sel.id === c.id }" @click="select(c)">
          <div class="live-item-top">
            <b>{{ c.userId }}</b>
            <el-tag size="small" :type="c.takeoverStatus === 'PENDING' ? 'warning' : 'success'">
              {{ c.takeoverStatus === 'PENDING' ? '待接管' : (c.handler || '') }}
            </el-tag>
          </div>
          <div class="live-item-msg">[{{ roleLbl(c.lastRole) }}] {{ c.lastMessage }}</div>
          <div class="live-item-time">{{ fmtTime(c.updateTime) }}</div>
        </div>
      </div>

      <!-- 右：对话区 -->
      <div class="live-chat" v-if="sel">
        <div class="live-chat-head">
          <b>{{ sel.userId }} · {{ sel.title || '会话' }}</b>
          <div style="display:flex; gap:8px">
            <el-button v-if="sel.takeoverStatus === 'PENDING'" type="primary" size="small" round @click="doTakeover">接管会话</el-button>
            <el-button v-if="sel.takeoverStatus === 'ACTIVE'" type="success" size="small" round plain @click="doRelease">结束并恢复 AI</el-button>
          </div>
        </div>
        <div class="live-msgs" ref="msgsEl">
          <div v-for="(m, i) in msgs" :key="i"
               :style="{ display: m.role === 'SYSTEM' ? 'block' : 'flex', justifyContent: (m.role === 'AGENT' ? 'flex-end' : 'flex-start'), marginBottom: '10px' }">
            <div v-if="m.role === 'SYSTEM'" class="sys">{{ m.content }}</div>
            <div v-else :class="m.role === 'AGENT' ? 'b-me' : 'b-them'">
              <div class="b-role">{{ roleLbl(m.role) }}</div>
              <div style="white-space:pre-wrap">{{ m.content }}</div>
            </div>
          </div>
        </div>
        <div class="live-input">
          <el-input v-model="input" type="textarea" :rows="2" placeholder="以坐席身份回复客户…（Ctrl+Enter 发送）"
                    @keydown.ctrl.enter.prevent="send" :disabled="sel.takeoverStatus !== 'ACTIVE'" />
          <el-button type="primary" round :disabled="!input.trim() || sel.takeoverStatus !== 'ACTIVE'" @click="send">发送</el-button>
        </div>
      </div>
      <div class="live-chat live-none" v-else>← 选择左侧会话开始接待（接管后 AI 暂停自动应答）</div>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'TicketLivePage' })
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const tab = ref('PENDING'), list = ref([]), sel = ref(null), msgs = ref([]), input = ref('')
const msgsEl = ref()
let timer = null

const filteredList = computed(() => tab.value === 'ALL' ? list.value : list.value.filter(c => c.takeoverStatus === tab.value))
const countBy = st => list.value.filter(c => c.takeoverStatus === st).length
const roleLbl = r => ({ USER: '客户', ASSISTANT: 'AI 助手', AGENT: '人工坐席', SYSTEM: '系统' }[r] || r)
const fmtTime = t => (t || '').replace('T', ' ').slice(5, 16)

async function load() {
  try {
    list.value = (await api.get('/v1/conversations/human-list')) || []
    if (sel.value) {
      const fresh = list.value.find(c => c.id === sel.value.id)
      if (fresh) sel.value = { ...sel.value, ...fresh }
      else await loadMsgs()  // 已释放出列表，保留会话只刷新消息
    }
  } catch (e) { /* 轮询失败忽略 */ }
}

async function loadMsgs() {
  if (!sel.value) return
  msgs.value = (await api.get(`/v1/conversations/${sel.value.id}/messages`)) || []
  nextTick(() => { if (msgsEl.value) msgsEl.value.scrollTop = msgsEl.value.scrollHeight })
}

async function select(c) {
  sel.value = c
  await loadMsgs()
}

async function doTakeover() {
  await api.post(`/v1/conversations/${sel.value.id}/takeover`)
  ElMessage.success('已接管，客户已收到通知')
  await load(); await loadMsgs()
}

async function doRelease() {
  await ElMessageBox.confirm('结束后 AI 将恢复自动应答，确认？', '结束接待', { type: 'warning' })
  await api.post(`/v1/conversations/${sel.value.id}/release`)
  ElMessage.success('已结束，AI 已恢复')
  await load(); await loadMsgs()
}

async function send() {
  const content = input.value.trim()
  if (!content || !sel.value || sel.value.takeoverStatus !== 'ACTIVE') return
  await api.post(`/v1/conversations/${sel.value.id}/agent-message`, { content })
  input.value = ''
  await loadMsgs()
}

onMounted(() => {
  load()
  timer = setInterval(() => { load(); if (sel.value) loadMsgs() }, 3000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.live-wrap { display:flex; gap:14px; height: calc(100vh - 150px); min-height: 480px; }
.live-list { width: 300px; flex-shrink: 0; background: var(--el-bg-color); border:1px solid var(--el-border-color-lighter);
  border-radius: 14px; padding: 12px; overflow-y: auto; }
.live-list-head { display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; flex-wrap:wrap; gap:6px; }
.live-empty { color: var(--el-text-color-secondary); font-size: 13px; text-align:center; padding: 40px 0; }
.live-item { border:1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 10px; margin-bottom: 8px; cursor: pointer; transition: background .15s; }
.live-item:hover { background: var(--el-fill-color-light); }
.live-item.sel { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.live-item-top { display:flex; justify-content:space-between; align-items:center; margin-bottom: 4px; }
.live-item-msg { font-size: 12.5px; color: var(--el-text-color-regular); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.live-item-time { font-size: 11.5px; color: var(--el-text-color-secondary); margin-top: 3px; }
.live-chat { flex:1; display:flex; flex-direction:column; background: var(--el-bg-color); border:1px solid var(--el-border-color-lighter); border-radius: 14px; overflow: hidden; }
.live-chat-head { display:flex; justify-content:space-between; align-items:center; padding: 12px 16px; border-bottom: 1px solid var(--el-border-color-lighter); }
.live-msgs { flex:1; overflow-y:auto; padding: 16px; }
.live-input { display:flex; gap:10px; align-items:flex-end; padding: 12px 16px; border-top: 1px solid var(--el-border-color-lighter); }
.live-none { align-items:center; justify-content:center; color: var(--el-text-color-secondary); }
.sys { text-align:center; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); border-radius: 8px; padding: 5px 10px; }
.b-me, .b-them { max-width: 76%; border-radius: 14px; padding: 9px 13px; font-size: 13.5px; line-height: 1.6; }
.b-me { background: var(--el-color-primary); color: #fff; border-bottom-right-radius: 4px; }
.b-them { background: var(--el-fill-color-light); border-bottom-left-radius: 4px; }
.b-role { font-size: 11px; opacity: .75; margin-bottom: 3px; }
</style>
