<template>
  <div style="max-width:460px; margin:24px auto; display:flex; flex-direction:column; height:calc(100vh - 48px); background:#fff; border-radius:16px; box-shadow:0 8px 32px rgba(0,0,0,.12); overflow:hidden">
    <div style="padding:14px 18px; border-bottom:1px solid #eee; display:flex; justify-content:space-between; align-items:center">
      <b>{{ appName || 'AI 助手' }}</b>
      <span style="font-size:11px; color:#999">Powered by DevHub</span>
    </div>
    <div ref="listEl" style="flex:1; overflow-y:auto; padding:16px; background:#f7f8fa">
      <div v-if="greeting" style="margin-bottom:14px">
        <div class="wb">{{ greeting }}</div>
      </div>
      <div v-for="(m, i) in msgs" :key="i" :style="{ display:'flex', justifyContent: m.role === 'USER' ? 'flex-end' : 'flex-start', marginBottom:'12px' }">
        <div class="wb" :class="{ me: m.role === 'USER' }" style="white-space:pre-wrap">{{ m.content || '…' }}</div>
      </div>
    </div>
    <div style="display:flex; gap:8px; padding:12px; border-top:1px solid #eee">
      <el-input v-model="input" :disabled="busy" placeholder="输入问题，Enter 发送" @keydown.enter.exact.prevent="send" />
      <el-button type="primary" :disabled="!input.trim() || busy" @click="send">发送</el-button>
    </div>
  </div>
</template>

<script setup>
defineOptions({ name: 'EmbedWidget' })
import { ref, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElInput, ElButton } from 'element-plus'
import { useSseChat } from '../../composables/useSseChat'

const route = useRoute()
const appKey = route.params.appKey
const appName = ref(''), greeting = ref('')
const msgs = ref([]), input = ref(''), busy = ref(false)
const listEl = ref()
const { stream } = useSseChat()
const convId = () => localStorage.getItem('dh_embed_' + appKey) || ''
const setConv = id => localStorage.setItem('dh_embed_' + appKey, id)

onMounted(async () => {
  try {
    const r = await fetch(`/api/open/v1/widget/meta/${appKey}`)
    const meta = await r.json()
    if (meta && meta.data) { appName.value = meta.data.name; greeting.value = meta.data.greeting || '' }
  } catch (e) { /* 拿不到就静默 */ }
})

function scrollBottom() { nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight }) }

async function send() {
  const q = input.value.trim()
  if (!q || busy.value) return
  input.value = ''
  msgs.value.push({ role: 'USER', content: q })
  const ai = { role: 'ASSISTANT', content: '' }
  msgs.value.push(ai)
  busy.value = true
  scrollBottom()
  try {
    const raw = await stream('/api/open/v1/widget/stream',
      { appKey, message: q, conversationId: convId() },
      { onMeta: m => { if (m.conversationId) setConv(m.conversationId.split(':').pop()) },
        onDelta: t => { ai.content = t; scrollBottom() } })
    ai.content = raw || '（未返回内容）'
  } catch (e) {
    ai.content = '服务暂时不可用，请稍后再试'
  } finally {
    busy.value = false
    scrollBottom()
  }
}
</script>

<style scoped>
.wb { background:#fff; border-radius:12px; padding:10px 14px; max-width:82%; font-size:13.5px; line-height:1.7; box-shadow:0 1px 2px rgba(0,0,0,.06) }
.wb.me { background:var(--el-color-primary); color:#fff }
</style>
