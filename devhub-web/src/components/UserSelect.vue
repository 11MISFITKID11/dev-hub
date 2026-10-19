<template>
  <el-select v-model="model" :placeholder="placeholder || '成员'" clearable filterable :style="{ width: width || '180px' }">
    <el-option v-for="u in opts.users" :key="u.username" :label="u.nickname || u.username" :value="u.username" />
  </el-select>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useOptionsStore } from '../stores/options'

const props = defineProps({ modelValue: String, placeholder: String, width: String })
const emit = defineEmits(['update:modelValue'])
const opts = useOptionsStore()
const model = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})
onMounted(() => opts.load())
</script>
