<template>
  <el-tag :type="typeOf(value)" size="small" effect="light">{{ label }}</el-tag>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '../stores/user'

const props = defineProps({
  dict: { type: String, required: true }, // 字典键名，如 task_status
  value: { type: [String, Number], default: '' }
})

const store = useUserStore()

// 全局统一的枚举语义色（vue-element-admin statusMap 模式）
const TYPE_MAPS = {
  task_status: { TODO: 'info', DOING: 'primary', DONE: 'success' },
  task_priority: { L: 'info', M: 'primary', H: 'warning', U: 'danger' },
  defect_status: { OPEN: 'danger', FIXED: 'primary', CLOSED: 'success' },
  defect_severity: { FATAL: 'danger', SERIOUS: 'warning', NORMAL: 'primary', MINOR: 'info' },
  project_status: { 0: 'primary', 1: 'info' }
}

const label = computed(() =>
  ((store.dicts[props.dict] || []).find(o => String(o.value) === String(props.value)) || {}).label || props.value
)
const typeOf = v => (TYPE_MAPS[props.dict] || {})[String(v)] || 'info'
</script>
