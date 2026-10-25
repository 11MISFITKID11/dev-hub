<template>
  <div class="login-wrap">
    <div class="login-card">
      <!-- 左：品牌与能力面板 -->
      <div class="brand-panel">
        <div class="brand-row">
          <div class="logo">HH</div>
          <div>
            <div class="brand-name">HelpHub</div>
            <div class="brand-slogan">AI 智能客服与工单处理系统</div>
          </div>
        </div>

        <div class="chat-demo">
          <div class="bubble me">退款政策里 7 天无理由怎么算？</div>
          <div class="bubble ai">自签收之日起 7 个自然日内可无理由退货，以下是依据条款…</div>
        </div>

        <div class="features">
          <div class="feat">
            <el-icon :size="18"><ChatDotRound /></el-icon>
            <div class="feat-t">知识问答</div>
            <div class="feat-d">企业知识库向量检索，答案带出处</div>
          </div>
          <div class="feat">
            <el-icon :size="18"><Service /></el-icon>
            <div class="feat-t">人工协同</div>
            <div class="feat-d">AI 答不了自动转坐席，AI 辅助起草</div>
          </div>
          <div class="feat">
            <el-icon :size="18"><Tickets /></el-icon>
            <div class="feat-t">工单闭环</div>
            <div class="feat-d">分类定级 · 处理 · 质检 · 满意度</div>
          </div>
        </div>
      </div>

      <!-- 右：登录 / 注册表单 -->
      <div class="form-panel">
        <h2>{{ regMode ? '注册客户账号' : '账号登录' }}</h2>
        <p class="sub">{{ regMode ? '注册后即可向 AI 客服提问、提交工单' : '知识问答 · 人工协同 · 工单闭环' }}</p>
        <el-form @submit.prevent="doLogin">
          <el-form-item><el-input v-model="u" placeholder="用户名" size="large" autocomplete="username" /></el-form-item>
          <el-form-item><el-input v-model="p" type="password" placeholder="密码" size="large" show-password @keyup.enter="regMode ? doRegister() : doLogin()" /></el-form-item>
          <el-button v-if="!regMode" type="primary" size="large" class="login-btn" :loading="loading" @click="doLogin">登 录</el-button>
          <el-button v-else type="primary" size="large" class="login-btn" :loading="loading" @click="doRegister">注 册</el-button>
        </el-form>
        <div class="hint">坐席账号：admin / admin123 · 客户账号：dev01 / dev123</div>
        <div style="margin-top:10px; font-size:12.5px">
          <a v-if="!regMode" href="javascript:;" style="color:var(--el-color-primary)" @click="regMode = true">没有账号？客户自助注册</a>
          <a v-else href="javascript:;" style="color:var(--el-color-primary)" @click="regMode = false">已有账号？返回登录</a>
        </div>
      </div>
    </div>
    <div class="login-footer">HelpHub · AI 自动答 · 人工跟单 · 质检闭环</div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChatDotRound, Service, Tickets } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'

const u = ref(''), p = ref(''), loading = ref(false), regMode = ref(false)
const router = useRouter(), store = useUserStore()

async function doRegister() {
  if (!u.value || !p.value) return
  loading.value = true
  try {
    await store.login(u.value, p.value, '/auth/register')
    router.push('/ai/chat')
  } catch (e) {
    /* 拦截器已统一弹错 */
  } finally { loading.value = false }
}
async function doLogin() {
  if (!u.value || !p.value) return
  loading.value = true
  try {
    await store.login(u.value, p.value)
    await store.loadPerms()   // 权限点就绪后再进应用，避免 v-perm 按钮被误删
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
.login-wrap::before {
  content: '';
  position: absolute;
  inset: -20%;
  z-index: 0;
  background:
    radial-gradient(1000px 720px at 78% 18%, rgba(0, 113, 227, 0.20), transparent 58%),
    radial-gradient(820px 580px at 16% 84%, rgba(0, 113, 227, 0.10), transparent 60%);
}
.login-card {
  position: relative;
  z-index: 1;
  display: flex;
  width: 860px;
  max-width: calc(100vw - 32px);
  min-height: 520px;
  border-radius: var(--dh-radius-float, 22px);
  overflow: hidden;
  background: var(--dh-glass-bg);
  border: 1px solid var(--dh-glass-border);
  -webkit-backdrop-filter: saturate(180%) blur(32px);
  backdrop-filter: saturate(180%) blur(32px);
  box-shadow: var(--dh-shadow-float);
}

/* 左侧品牌面板：深墨蓝底（同色系明度差，无花哨渐变） */
.brand-panel {
  flex: 1 1 52%;
  background: #0d2b52;
  color: #eef3fa;
  padding: 34px 34px 28px;
  display: flex;
  flex-direction: column;
}
.brand-row { display: flex; align-items: center; gap: 12px; }
.logo {
  width: 44px; height: 44px; border-radius: 13px; flex-shrink: 0;
  background: var(--el-color-primary);
  color: #fff; font-weight: 800; font-size: 17px; line-height: 44px; text-align: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.25);
}
.brand-name { font-weight: 700; font-size: 28px; letter-spacing: -0.022em; }
.brand-slogan { color: #a9bdd8; font-size: 13px; margin-top: 6px; letter-spacing: 0.01em; }

.chat-demo { margin: 30px 0 6px; display: flex; flex-direction: column; gap: 12px; }
.bubble {
  max-width: 86%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 12.5px;
  line-height: 1.7;
}
.bubble.me { align-self: flex-end; background: var(--el-color-primary); color: #fff; border-bottom-right-radius: 4px; }
.bubble.ai { align-self: flex-start; background: rgba(255, 255, 255, 0.10); color: #dbe6f5; border-bottom-left-radius: 4px; }

.features { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
.feat {
  display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.10);
}
.feat .el-icon { color: #8fb8f0; align-self: center; }
.feat-t { font-size: 13px; font-weight: 600; align-self: center; }
.feat-d { width: 100%; color: #b9c8de; font-size: 11.5px; margin-top: 2px; }

/* 右侧表单面板 */
.form-panel { flex: 1 1 48%; background: transparent; padding: 44px 40px; }
h2 { font-size: 20px; margin-bottom: 4px; letter-spacing: .2px; }
.sub { color: var(--el-text-color-secondary); font-size: 12.5px; margin-bottom: 22px; }
.login-btn {
  width: 100%;
  margin-top: 4px;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.18);
  transition: transform .15s ease, box-shadow .15s ease;
}
.login-btn:hover { transform: translateY(-1px); }
.hint { margin-top: 16px; color: var(--el-text-color-regular); font-size: 12px; line-height: 1.8; }
.login-footer { position: relative; z-index: 1; margin-top: 22px; color: var(--el-text-color-placeholder); font-size: 12px; }

@media (max-width: 720px) {
  .brand-panel { display: none; }
  .login-card { width: 400px; min-height: 0; }
}
</style>
