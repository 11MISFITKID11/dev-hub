<template>
  <div style="display:flex; gap:14px; height: calc(100vh - 130px)">
    <!-- 会话列表 -->
    <div style="width:216px; flex-shrink:0; padding-right:10px; display:flex; flex-direction:column">
      <el-button type="primary" size="small" style="margin-bottom:10px; border-radius:10px" @click="newConv">＋ 新会话</el-button>
      <div style="flex:1; overflow-y:auto; padding:6px; border-radius:14px; background:rgba(255,255,255,.42); border:1px solid var(--dh-glass-border, rgba(255,255,255,.55)); -webkit-backdrop-filter:saturate(160%) blur(18px); backdrop-filter:saturate(160%) blur(18px)">
        <div v-for="c in convs" :key="c.id" class="conv-item" :class="{ on: cur && cur.id === c.id }" @click="openConv(c)">
          {{ (c.title || c.id).slice(0, 20) }}
        </div>
      </div>
    </div>

    <!-- 对话区 -->
    <div style="flex:1; display:flex; flex-direction:column; min-width:0">
      <div ref="listEl" style="flex:1; overflow-y:auto; padding-right:8px">
        <div v-if="!msgs.length" style="text-align:center; color:var(--el-text-color-secondary); padding-top:80px; font-size:13px">
          提问基于已上传文档作答 · 每句带出处 · 拒答不编造
        </div>
        <div v-for="(m, i) in msgs" :key="i" :style="{ display:'flex', justifyContent: m.role === 'USER' ? 'flex-end' : 'flex-start', marginBottom:'14px' }">
          <div :class="m.role === 'USER' ? 'b-user' : 'b-ai'">
            <div v-if="m.role === 'USER'" style="white-space:pre-wrap">{{ m.content }}</div>
            <template v-else>
              <div class="md" v-html="m.html"></div>
              <div v-if="m.messageId && !streaming" style="margin-top:6px; display:flex; gap:6px; font-size:13px">
                <span class="fb" :class="{ on: m.feedback === 1 }" title="有用" @click="vote(m, 1)">👍</span>
                <span class="fb" :class="{ on: m.feedback === -1 }" title="无用，将记录知识缺口" @click="vote(m, -1)">👎</span>
              </div>
            </template>
          </div>
        </div>
        <div v-if="streaming && !msgs.length" style="color:var(--el-text-color-secondary); font-size:12.5px; padding:10px 0">
          <el-icon class="is-loading"><Loading /></el-icon> 思考中…
        </div>
      </div>
      <div v-if="curSources.length" style="padding:8px 0; border-top:1px solid var(--el-border-color-lighter); display:flex; gap:8px; flex-wrap:wrap">
        <el-tag v-for="(s, i) in curSources" :key="i" size="small" type="success" style="cursor:pointer" @click="srcOpen = s">
          📕 {{ (s.fileName || '').slice(0, 16) }} · {{ Math.round((s.score || 0) * 100) }}%
        </el-tag>
      </div>
      <div style="display:flex; gap:10px; padding-top:10px">
        <el-input v-model="input" :disabled="streaming" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }"
                  placeholder="输入问题，Enter 发送 · Shift+Enter 换行" @keydown.enter.exact.prevent="send" />
        <el-button v-if="!streaming" type="primary" @click="send" :disabled="!input.trim()" style="height:auto">发送</el-button>
        <el-button v-else type="danger" @click="abort" style="height:auto">停止</el-button>
      </div>
      <div style="display:flex; align-items:center; gap:8px; padding-top:6px">
        <el-switch v-model="ragOn" active-text="知识库检索" size="small" />
      </div>
    </div>

    <!-- 引用原文抽屉 -->
    <el-drawer v-model="srcDrawer" :title="srcOpen && srcOpen.fileName" size="35%">
      <div style="font-size:13px; line-height:1.8; white-space:pre-wrap">{{ srcOpen && srcOpen.content }}</div>
    </el-drawer>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChatPage' })
import { ref, computed, nextTick, onMounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import api from '../../api'

const convs = ref([]), cur = ref(null), msgs = ref([])
const input = ref(''), streaming = ref(false), ragOn = ref(true)
const listEl = ref(), ctrl = ref(null), srcOpen = ref(null)
const srcDrawer = ref(false)
const curSources = computed(() => {
  const last = [...msgs.value].reverse().find(m => m.role === 'ASSISTANT')
  return (last && last.sources) || []
})

async function loadConvs() {
  convs.value = (await api.get('/v1/conversations')) || []
}
function newConv() { cur.value = null; msgs.value = [] }
async function openConv(c) {
  cur.value = c
  const ms = await api.get(`/v1/conversations/${c.id}/messages`)
  msgs.value = (ms || []).map(m => ({ role: m.role, content: m.content, html: m.role === 'ASSISTANT' ? render(m.content) : '' }))
}
function render(text) {
  let fences = (text.match(/```/g) || []).length
  if (fences % 2 === 1) text += '\n```'
  const html = marked.parse(text, { breaks: true, gfm: true })
  return DOMPurify.sanitize(html)
}
async function vote(m, v) {
  if (m.feedback === v) return
  await api.post(`/v1/chat/messages/${m.messageId}/feedback`, { value: v })
  m.feedback = v
}
function scrollBottom() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }

async function send() {
  const q = input.value.trim()
  if (!q || streaming.value) return
  input.value = ''
  msgs.value.push({ role: 'USER', content: q })
  const aiMsg = { role: 'ASSISTANT', content: '', html: '', sources: [] }
  msgs.value.push(aiMsg)
  streaming.value = true; ctrl.value = new AbortController()
  scrollBottom()

  try {
    const token = localStorage.getItem('dh_token')
    const res = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({
        conversationId: cur.value && cur.value.id,
        message: q, enableRag: ragOn.value, mode: 'DIRECT'
      }),
      signal: ctrl.value.signal
    })
    const reader = res.body.getReader(), dec = new TextDecoder()
    let buf = '', raw = '', renderTick = 0
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const events = buf.split('\n\n'); buf = events.pop()
      for (const evt of events) {
        const line = evt.split('\n').find(l => l.startsWith('data:'))
        if (!line) continue
        const data = line.slice(5)
        if (data === '[DONE]') continue
        if (data.startsWith('__META__')) {
          const meta = JSON.parse(data.slice(8))
          if (meta.conversationId && !cur.value) {
            cur.value = { id: meta.conversationId, title: q }
            loadConvs()
          }
          aiMsg.sources = meta.sources || []
          aiMsg.messageId = meta.messageId
        } else {
          raw += data
          if (!renderTick) {
            renderTick = setTimeout(() => {
              renderTick = 0
              aiMsg.html = render(raw) + '<span style="display:inline-block;width:8px;height:16px;background:var(--el-color-primary);animation:blink 1s infinite"></span>'
              scrollBottom()
            }, 150)
          }
        }
      }
    }
    if (renderTick) clearTimeout(renderTick)
    aiMsg.content = raw || '（未返回内容）'
    aiMsg.html = render(aiMsg.content)
    // 代码高亮
    nextTick(() => { document.querySelectorAll('.md pre code').forEach(el => hljs.highlightElement(el)) })
  } catch (e) {
    if (e.name !== 'AbortError') aiMsg.html = render('请求失败：' + e.message)
  } finally {
    streaming.value = false; scrollBottom(); loadConvs()
  }
}
function abort() { if (ctrl.value) ctrl.value.abort() }

onMounted(loadConvs)
</script>

<style scoped>
.fb { cursor:pointer; opacity:.45; transition:all .15s; user-select:none }
.fb:hover { opacity:.9 }
.fb.on { opacity:1; transform:scale(1.15) }
</style>

<style scoped>
.conv-item {
  padding: 9px 12px;
  border-radius: 11px;
  font-size: 12.5px;
  cursor: pointer;
  margin-bottom: 4px;
  border: 1px solid transparent;
  transition: background .15s ease, border-color .15s ease;
}
.conv-item:hover { background: rgba(0, 113, 227, 0.07); }
html.dark .conv-item:hover { background: rgba(79, 148, 234, 0.12); }
.conv-item.on {
  background: var(--el-color-primary);
  color: #fff;
}
.b-user {
  background: linear-gradient(135deg, #0071e3, #4098e0);
  color: #fff;
  padding: 10px 16px;
  border-radius: 18px 18px 5px 18px;
  max-width: 72%;
  font-size: 14px;
  box-shadow: 0 4px 14px rgba(0, 113, 227, 0.28);
}
.b-ai {
  background: rgba(255, 255, 255, 0.66);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  backdrop-filter: saturate(180%) blur(20px);
  border: 1px solid var(--dh-glass-border, rgba(255,255,255,.55));
  box-shadow: 0 2px 10px rgba(0,0,0,.05);
  padding: 12px 16px;
  border-radius: 5px 18px 18px 18px;
  max-width: 88%;
  font-size: 14px;
}
html.dark .b-ai { background: rgba(36, 36, 40, 0.66); }
.md :deep(p) { margin: 0 0 8px; } .md :deep(pre) { background: #0d1117; color: #e6edf3; padding: 12px; border-radius: 12px; overflow-x: auto; font-size: 13px; }
.md :deep(code) { background: rgba(0,113,227,.1); padding: 1px 6px; border-radius: 5px; font-size: 0.9em; }
.md :deep(pre code) { background: none; padding: 0; }
.md :deep(table) { border-collapse: collapse; } .md :deep(th), .md :deep(td) { border: 1px solid var(--el-border-color); padding: 5px 10px; font-size: 13px; }
@keyframes blink { 50% { opacity: 0; } }
</style>
