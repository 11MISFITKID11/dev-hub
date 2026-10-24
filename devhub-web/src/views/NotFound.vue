<template>
  <div class="nf-wrap">
    <el-card class="nf-card" shadow="never">
      <div class="code">404</div>
      <p class="tip">页面不存在或没有访问权限</p>
      <el-button type="primary" round @click="$router.push('/ai/chat')">返回首页</el-button>
    </el-card>
  </div>
</template>

<script setup>
defineOptions({ name: 'NotFound' })
</script>

<style scoped>
.nf-wrap { height: 100vh; display: flex; align-items: center; justify-content: center; }
.nf-card { width: 360px; text-align: center; border-radius: var(--dh-radius-float, 22px); }
.code {
  font-size: 64px; font-weight: 800; letter-spacing: -2px; line-height: 1;
  color: var(--el-color-primary);
  font-family: var(--dh-font-mono); font-variant-numeric: tabular-nums;
}
.tip { color: var(--el-text-color-secondary); font-size: 13px; margin: 12px 0 20px; }
</style>
