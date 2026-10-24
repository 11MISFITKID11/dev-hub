<template>
  <div class="login-wrap">
    <el-card class="login-card" shadow="never">
      <div class="login-brand">
        <div class="logo">DH</div>
        <div>
          <div class="brand-name">DevHub</div>
          <div class="brand-slogan">研发管理系统</div>
        </div>
      </div>
      <h2>欢迎回来</h2>
      <p class="sub">登录后使用全部功能</p>
      <el-form @submit.prevent="doLogin">
        <el-form-item><el-input v-model="u" placeholder="用户名" size="large" autocomplete="username" /></el-form-item>
        <el-form-item><el-input v-model="p" type="password" placeholder="密码" size="large" show-password @keyup.enter="doLogin" /></el-form-item>
        <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="doLogin">登 录</el-button>
      </el-form>
      <div class="hint">骨架账号：admin / admin123（管理员）· dev01 / dev123（普通用户）</div>
    </el-card>
    <div class="login-footer">DevHub · 智能研发协作平台</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const u = ref(''), p = ref(''), loading = ref(false)
const router = useRouter(), store = useUserStore()

async function doLogin() {
  if (!u.value || !p.value) return
  loading.value = true
  try {
    await store.login(u.value, p.value)
    router.push('/ai/chat')
  } catch (e) {
    /* 拦截器已统一弹错，这里不再重复提示 */
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background: var(--dh-page-bg, #f5f5f7);
}
/* macOS 登录屏式鲜艳极光壁纸 */
.login-wrap::before {
  content: '';
  position: absolute;
  inset: -20%;
  z-index: 0;
  background:
    radial-gradient(1000px 720px at 78% 18%, rgba(0, 113, 227, 0.20), transparent 58%),
    radial-gradient(820px 580px at 16% 84%, rgba(0, 113, 227, 0.10), transparent 60%);
  animation: aurora-drift 26s ease-in-out infinite alternate;
}
@keyframes aurora-drift {
  0%   { transform: translate3d(0, 0, 0) scale(1); }
  50%  { transform: translate3d(2%, -2%, 0) scale(1.06); }
  100% { transform: translate3d(-2%, 2%, 0) scale(1.02); }
}
.login-card {
  position: relative;
  z-index: 1;
  width: 384px;
  border-radius: var(--dh-radius-float, 22px);
  border: 1px solid var(--dh-glass-border);
  background: var(--dh-glass-bg);
  -webkit-backdrop-filter: saturate(180%) blur(32px);
  backdrop-filter: saturate(180%) blur(32px);
  box-shadow: var(--dh-shadow-float);
}
.login-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.logo {
  width: 44px; height: 44px; border-radius: 13px; flex-shrink: 0;
  background: var(--el-color-primary);
  color: #fff; font-weight: 800; font-size: 18px; line-height: 44px; text-align: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
}
.brand-name { font-weight: 700; font-size: 16px; letter-spacing: .3px; }
.brand-slogan { color: var(--el-text-color-secondary); font-size: 12px; }
h2 { font-size: 20px; margin-bottom: 4px; letter-spacing: .2px; }
.sub { color: var(--el-text-color-secondary); font-size: 12.5px; margin-bottom: 20px; }
.login-btn {
  width: 100%;
  margin-top: 4px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
}
.login-btn:hover { transform: translateY(-1px); }
.login-btn { transition: transform .15s ease, box-shadow .15s ease; }
.hint { margin-top: 16px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.8; }
.login-footer { position: relative; z-index: 1; margin-top: 22px; color: var(--el-text-color-placeholder); font-size: 12px; }
</style>
