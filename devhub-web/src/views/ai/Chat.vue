<template>
  <div>
    <!-- 接待台头部 + 模型/参数工具栏 -->
    <div class="desk-head">
      <div>
        <div class="desk-title">AI 客服接待台</div>
        <div class="desk-sub">基于已向量化的客服知识片段回答客户问题 · 拒答不编造，答不了转人工</div>
      </div>
      <div class="desk-tools">
        <template v-if="isAdminSide">
          <el-select v-model="modelName" size="small" style="width:168px" placeholder="默认模型" clearable>
            <el-option v-for="m in models" :key="m.name" :value="m.name" :label="m.name">
              <span>{{ m.name }}</span><span class="m-desc">{{ m.description }}</span>
            </el-option>
          </el-select>
          <div class="temp-box">
            <span class="temp-lbl">温度</span>
            <el-slider v-model="temperature" :min="0" :max="1" :step="0.1" :show-tooltip="false" style="width:84px" />
            <span class="temp-val dh-num">{{ temperature.toFixed(1) }}</span>
          </div>
        </template>
        <el-switch v-model="ragOn" active-text="知识库检索" size="small" />
      </div>
    </div>

    <div style="display:flex; gap:14px; height: calc(100vh - 196px)">
    <!-- 会话列表 -->
    <div style="width:216px; flex-shrink:0; padding-right:10px; display:flex; flex-direction:column">
      <el-button type="primary" size="small" style="margin-bottom:10px; border-radius:10px" @click="newConv">＋ 新会话</el-button>
      <div style="flex:1; overflow-y:auto; padding:6px; border-radius:14px; background:rgba(255,255,255,.42); border:1px solid var(--dh-glass-border, rgba(255,255,255,.55)); -webkit-backdrop-filter:saturate(160%) blur(18px); backdrop-filter:saturate(160%) blur(18px)">
        <div v-for="c in convs" :key="c.id" class="conv-item" :class="{ on: cur && cur.id === c.id }" @click="openConv(c)">
          <span class="conv-title">{{ c.title || c.id }}</span>
        </div>
      </div>
    </div>

    <!-- 对话区 -->
    <div style="flex:1; display:flex; flex-direction:column; min-width:0">
      <div ref="listEl" style="flex:1; overflow-y:auto; padding-right:8px">
        <!-- 服务首页 hero：大输入框直问 -->
        <div v-if="!msgs.length" class="hero">
          <div class="hero-title">您好，需要什么帮助？</div>
          <div class="hero-sub">可以咨询产品、订单、退款、部署和售后问题，回答基于已启用知识库</div>
          <div class="hero-box">
            <el-input
              v-model="input" type="textarea" :rows="4" maxlength="2000" resize="none"
              placeholder="请描述您的问题…" :disabled="streaming"
              @keydown.ctrl.enter.prevent="send" />
          </div>
          <div class="hero-foot">
            <span class="hero-hint">Ctrl + Enter 发送</span>
            <span class="hero-count dh-num">{{ input.length }}/2000</span>
            <el-button v-if="!streaming" type="primary" round :disabled="!input.trim()" @click="send">发送问题</el-button>
            <el-button v-else type="danger" round @click="abort">停止</el-button>
          </div>
          <div style="display:flex; gap:10px; justify-content:center; flex-wrap:wrap; margin-top:26px">
            <span v-for="q in suggestions" :key="q" class="sug-chip" @click="input = q">{{ q }}</span>
          </div>
        </div>
        <div v-for="(m, i) in msgs" :key="i" :style="{ display:'flex', justifyContent: m.role === 'USER' ? 'flex-end' : 'flex-start', marginBottom:'14px' }">
          <div :class="m.role === 'USER' ? 'b-user' : 'b-ai'">
            <div v-if="m.role === 'USER'" style="white-space:pre-wrap">{{ m.content }}</div>
            <template v-else>
              <div v-if="m.agentTools && m.agentTools.length" class="agent-chips">
                <span v-for="t in m.agentTools" :key="t" class="agent-chip">🔧 {{ TOOL_LBL[t] || t }}</span>
              </div>
              <div class="md" v-html="m.html"></div>
              <div v-if="m.messageId && !streaming" style="margin-top:6px; display:flex; gap:6px; font-size:13px; align-items:center">
                <span class="fb" :class="{ on: m.feedback === 1 }" title="有用" @click="vote(m, 1)">👍</span>
                <span class="fb" :class="{ on: m.feedback === -1 }" title="无用，将记录知识缺口" @click="vote(m, -1)">👎</span>
                <span v-if="m.refused" class="tk-btn" @click="openTicketDlg(m, 'chat_refusal')">转工单 · 人工跟进</span>
              </div>
            </template>
          </div>
        </div>
      </div>
      <div v-if="curSources.length" style="padding:8px 0; border-top:1px solid var(--el-border-color-lighter); display:flex; gap:8px; flex-wrap:wrap">
        <el-tag v-for="(s, i) in curSources" :key="i" size="small" type="success" style="cursor:pointer" @click="srcOpen = s">
          📕 {{ (s.fileName || '').slice(0, 16) }} · {{ Math.round((s.score || 0) * 100) }}%
        </el-tag>
      </div>
      <div v-if="msgs.length" style="display:flex; gap:10px; padding-top:10px">
        <el-input v-model="input" :disabled="streaming" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }"
                  placeholder="输入问题，Enter 发送 · Shift+Enter 换行" @keydown.enter.exact.prevent="send" />
        <el-button v-if="!streaming" type="primary" @click="send" :disabled="!input.trim()" style="height:auto">发送</el-button>
        <el-button v-else type="danger" @click="abort" style="height:auto">停止</el-button>
      </div>
    </div>

    <!-- 引用原文抽屉 -->
    <el-drawer v-model="srcDrawer" :title="srcOpen && srcOpen.fileName" size="35%">
      <div style="font-size:13px; line-height:1.8; white-space:pre-wrap">{{ srcOpen && srcOpen.content }}</div>
    </el-drawer>

    <!-- 转工单（拒答/点踩 → 人工闭环） -->
    <el-dialog v-model="tkDlg" title="转人工工单" width="520px">
      <el-form label-width="48px">
        <el-form-item label="标题"><el-input v-model="tkForm.title" maxlength="100" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="tkForm.content" type="textarea" :rows="7" /></el-form-item>
      </el-form>
      <div style="font-size:12px;color:var(--el-text-color-secondary)">提交后 AI 自动分类定级，坐席将在知识库辅助下人工处理。</div>
      <template #footer>
        <el-button @click="tkDlg = false">取消</el-button>
        <el-button type="primary" :loading="tkCreating" @click="submitTicket">提交工单</el-button>
      </template>
    </el-dialog>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'ChatPage' })
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import api from '../../api'
import { useUserStore } from '../../stores/user'

const store = useUserStore()
// 管理侧才展示模型/温度参数；客户门户保持纯问答界面
const isAdminSide = computed(() => store.allowedPaths.has('/tickets') || store.allowedPaths.has('/dashboard'))
const convs = ref([]), cur = ref(null), msgs = ref([])
const suggestions = ['退款政策里 7 天无理由怎么算？', '网关一直闪红灯连不上网怎么办？', '设备保修期是多久？怎么申请维修？']
const TOOL_LBL = { query_order: '已查询订单物流', create_ticket: '已创建工单' }
const input = ref(''), streaming = ref(false), ragOn = ref(true)
const listEl = ref(), ctrl = ref(null), srcOpen = ref(null)
const srcDrawer = ref(false)

/* ---- 模型与参数工具栏（持久化，随请求下发 modelOptions） ---- */
const models = ref([]), modelName = ref(''), temperature = ref(0.7)
try {
  const saved = JSON.parse(localStorage.getItem('dh_chat_opts') || '{}')
  if (saved.model !== undefined) modelName.value = saved.model
  if (saved.temperature !== undefined) temperature.value = saved.temperature
  if (saved.rag !== undefined) ragOn.value = saved.rag
} catch (e) { /* 忽略坏数据 */ }
watch([modelName, temperature, ragOn], () => {
  localStorage.setItem('dh_chat_opts', JSON.stringify({ model: modelName.value, temperature: temperature.value, rag: ragOn.value }))
}, { deep: true })
async function loadModels() {
  try {
    const d = await api.get('/v1/models')
    models.value = d.models || []
  } catch (e) { /* 工具栏降级：无模型列表时用默认路由 */ }
}

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
  if (v === -1) {
    // 点踩 = 知识缺口已记录；顺势引导转人工工单，闭环进入人工侧
    ElMessageBox.confirm('已记录知识缺口。要转人工工单跟进这个问题吗？', '转工单', { type: 'info' })
      .then(() => openTicketDlg(m, 'chat_dislike')).catch(() => {})
  }
}
/* ---- 转工单（拒答/点踩 → 人工闭环） ---- */
const tkDlg = ref(false), tkForm = ref({}), tkFrom = ref(null), tkCreating = ref(false)
function openTicketDlg(m, source) {
  tkFrom.value = m
  tkForm.value = {
    title: (m.question || '').slice(0, 60),
    content: (m.question || '') + '\n\n—— AI 回答（' + (source === 'chat_refusal' ? '拒答' : '被判无用') + '）：\n' + (m.content || '').slice(0, 400)
  }
  tkForm.value.source = source
  tkDlg.value = true
}
async function submitTicket() {
  tkCreating.value = true
  try {
    await api.post('/v1/tickets', {
      ...tkForm.value,
      conversationId: cur.value && cur.value.id,
      messageId: tkFrom.value && tkFrom.value.messageId
    })
    ElMessage.success('已转工单，可在「我的工单」跟进处理进度')
    tkDlg.value = false
  } finally { tkCreating.value = false }
}
function scrollBottom() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }

async function send() {
  const q = input.value.trim()
  if (!q || streaming.value) return
  input.value = ''
  msgs.value.push({ role: 'USER', content: q })
  const aiMsg = { role: 'ASSISTANT', content: '', html: '', sources: [], question: q }
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
        message: q, enableRag: ragOn.value, mode: 'DIRECT',
        modelOptions: {
          model: modelName.value || undefined,
          temperature: temperature.value
        }
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
        // SSE 规范：字段内换行编码为多个 data: 行，接收端 join('\n') 还原（缓存直返的整段答案含换行）
        const dataLines = evt.split('\n').filter(l => l.startsWith('data:'))
        if (!dataLines.length) continue
        const data = dataLines.map(l => l.slice(5)).join('\n')
        if (data === '[DONE]') continue
        if (data.startsWith('__META__')) {
          const meta = JSON.parse(data.slice(8))
          if (meta.conversationId && !cur.value) {
            cur.value = { id: meta.conversationId, title: q }
            loadConvs()
          }
          aiMsg.sources = meta.sources || []
          aiMsg.agentTools = meta.agentTools || []
          aiMsg.messageId = meta.messageId
          aiMsg.refused = !!meta.refused
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

onMounted(() => { loadConvs(); loadModels() })
</script>

<style scoped>
/* Agentic 动作标记 */
.agent-chips { display: flex; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
.agent-chip {
  font-size: 12px; color: var(--el-color-primary);
  background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px; padding: 1px 10px;
}
/* 接待台头部 */
.desk-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 14px; padding: 2px 2px 12px;
}
.desk-title { font-size: 17px; font-weight: 700; letter-spacing: .3px; }
.desk-sub { color: var(--el-text-color-secondary); font-size: 12.5px; margin-top: 3px; }
.desk-tools { display: flex; align-items: center; gap: 16px; flex-shrink: 0; }
.m-desc { float: right; color: var(--el-text-color-secondary); font-size: 12px; margin-left: 12px; }
.temp-box { display: flex; align-items: center; gap: 6px; }
.temp-lbl { font-size: 12.5px; color: var(--el-text-color-secondary); }
.temp-val { font-size: 12.5px; min-width: 22px; }

/* 服务首页 hero */
.hero { max-width: 640px; margin: 4vh auto 0; text-align: center; }
.hero-title { font-size: 22px; font-weight: 700; letter-spacing: .4px; }
.hero-sub { color: var(--el-text-color-secondary); font-size: 13px; margin: 8px 0 22px; }
.hero-box { text-align: left; }
.hero-foot { display: flex; align-items: center; gap: 12px; margin-top: 10px; justify-content: flex-end; }
.hero-hint { color: var(--el-text-color-placeholder); font-size: 12px; margin-right: auto; }
.hero-count { color: var(--el-text-color-secondary); font-size: 12px; }
.fb { cursor:pointer; opacity:.45; transition:opacity .15s ease; user-select:none }
.fb:hover { opacity:.9 }
.fb.on { opacity:1; transform:scale(1.15) }
.tk-btn {
  cursor:pointer; font-size:12px; color:var(--el-color-primary);
  border:1px solid var(--el-color-primary-light-7); border-radius:999px; padding:1px 10px;
  transition: color .15s ease, background-color .15s ease;
}
.tk-btn:hover { background:var(--el-color-primary-light-9) }
.sug-chip {
  padding: 7px 14px; border-radius: 999px; font-size: 13px; cursor: pointer;
  background: var(--dh-glass-bg); border: 1px solid var(--dh-glass-border, rgba(255,255,255,.55));
  color: var(--el-text-color-regular);
  transition: color .18s cubic-bezier(.4,0,.2,1), border-color .18s cubic-bezier(.4,0,.2,1), transform .18s cubic-bezier(.4,0,.2,1);
  -webkit-backdrop-filter: saturate(160%) blur(14px); backdrop-filter: saturate(160%) blur(14px);
}
.sug-chip:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); transform: translateY(-1px); }
.conv-item .conv-title {
  display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
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
  background: var(--el-color-primary);
  color: #fff;
  padding: 10px 16px;
  border-radius: 18px 18px 5px 18px;
  max-width: 72%;
  font-size: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
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
.sug-chip {
  padding: 7px 14px; border-radius: 999px; font-size: 13px; cursor: pointer;
  background: var(--dh-glass-bg); border: 1px solid var(--dh-glass-border, rgba(255,255,255,.55));
  color: var(--el-text-color-regular);
  transition: color .18s cubic-bezier(.4,0,.2,1), border-color .18s cubic-bezier(.4,0,.2,1), transform .18s cubic-bezier(.4,0,.2,1);
  -webkit-backdrop-filter: saturate(160%) blur(14px); backdrop-filter: saturate(160%) blur(14px);
}
.sug-chip:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); transform: translateY(-1px); }
.conv-item .conv-title {
  display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
</style>
