<template>
  <span class="gs-trigger" @click="visible = true" title="全局搜索 (Ctrl+K)">
    <el-icon :size="17"><Search /></el-icon>
    <span class="gs-trigger-kbd">⌘K</span>
  </span>
  <teleport to="body">
    <div v-if="visible" class="gs-mask" @click.self="visible = false">
      <div class="gs-panel">
        <div class="gs-input-row">
          <el-icon :size="18" style="color:var(--el-text-color-secondary)"><Search /></el-icon>
          <input ref="inputEl" v-model="q" class="gs-input" placeholder="搜索任务 / 缺陷 / 需求 / 文档 / 会话…"
                 @input="debouncedSearch" @keydown.esc="visible = false" />
          <span class="gs-kbd">ESC</span>
        </div>
        <div class="gs-body">
          <div v-if="!q" style="padding:26px 0; text-align:center; color:var(--el-text-color-secondary); font-size:12.5px">
            输入关键字，跨 5 类对象检索 · ⌘K / Ctrl+K 唤起
          </div>
          <template v-else>
            <div v-if="!searching && !hasResult" style="padding:26px 0; text-align:center; color:var(--el-text-color-secondary); font-size:12.5px">
              未找到「{{ q }}」相关内容
            </div>
            <div v-for="(items, group) in results" :key="group" v-show="items.length" class="gs-group">
              <div class="gs-group-title">{{ group }}</div>
              <div v-for="it in items" :key="group + it.id" class="gs-item" @click="go(it.url)">
                <span class="gs-title">{{ it.title }}</span>
                <span class="gs-meta">#{{ it.id }} · {{ it.status || '-' }}</span>
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'

const router = useRouter()
const visible = ref(false), q = ref(''), results = ref({}), searching = ref(false)
const inputEl = ref(null)
let timer = null

const hasResult = computed(() => Object.values(results.value).some(l => l.length))

async function doSearch() {
  if (!q.value.trim()) { results.value = {}; return }
  searching.value = true
  try { results.value = (await api.get('/search', { params: { q: q.value.trim() } })) || {} }
  catch (e) { results.value = {} } finally { searching.value = false }
}
function debouncedSearch() {
  clearTimeout(timer)
  timer = setTimeout(doSearch, 300)
}
function go(url) {
  visible.value = false
  if (url) router.push(url)
}
function onKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    visible.value = !visible.value
    if (visible.value) nextTick(() => inputEl.value?.focus())
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.gs-trigger {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  color: var(--el-text-color-secondary); padding: 4px 8px; border-radius: 9px;
  transition: background .15s ease, color .15s ease;
}
.gs-trigger:hover { background: rgba(0, 113, 227, 0.08); color: var(--el-color-primary); }
.gs-trigger-kbd { font-size: 10px; border: 1px solid var(--dh-hairline); border-radius: 5px; padding: 1px 4px; }
.gs-mask {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(0, 0, 0, 0.32);
  -webkit-backdrop-filter: blur(6px); backdrop-filter: blur(6px);
  display: flex; justify-content: center; align-items: flex-start; padding-top: 12vh;
}
.gs-panel {
  width: 560px; max-height: 60vh; display: flex; flex-direction: column; overflow: hidden;
  background: var(--dh-glass-3-bg, rgba(252,252,253,.9));
  -webkit-backdrop-filter: saturate(190%) blur(36px); backdrop-filter: saturate(190%) blur(36px);
  border: 1px solid var(--dh-glass-border); border-radius: 18px;
  box-shadow: var(--dh-shadow-float);
}
.gs-input-row { display: flex; align-items: center; gap: 10px; padding: 14px 16px; border-bottom: 1px solid var(--dh-hairline); }
.gs-input { flex: 1; border: none; outline: none; background: transparent; font-size: 15px; color: var(--el-text-color-primary); }
.gs-kbd { font-size: 10px; color: var(--el-text-color-placeholder); border: 1px solid var(--dh-hairline); border-radius: 5px; padding: 2px 5px; }
.gs-body { overflow-y: auto; padding: 8px; }
.gs-group-title { font-size: 11px; color: var(--el-text-color-secondary); padding: 8px 10px 4px; font-weight: 600; }
.gs-item {
  display: flex; justify-content: space-between; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: 10px; cursor: pointer; font-size: 13px;
}
.gs-item:hover { background: rgba(0, 113, 227, 0.08); }
.gs-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gs-meta { font-size: 11px; color: var(--el-text-color-placeholder); flex-shrink: 0; }
</style>
