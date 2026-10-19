<template>
  <el-select v-model="model" :placeholder="placeholder || '项目'" clearable filterable :style="{ width: width || '180px' }">
    <el-option v-for="p in opts.projects" :key="p.id" :label="p.name" :value="p.id" />
  </el-select>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useOptionsStore } from '../stores/options'

const props = defineProps({ modelValue: [String, Number], placeholder: String, width: String })
const emit = defineEmits(['update:modelValue'])
const opts = useOptionsStore()
const model = computed({
  get: () => props.modelValue,
  set: v => emit('update:modelValue', v)
})
onMounted(() => opts.load())
</script>
