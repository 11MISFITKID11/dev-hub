<template>
  <el-container style="height: 100vh">
    <el-aside :width="collapsed ? '64px' : '220px'" class="dh-rail" style="transition: width .25s">
      <div class="brand" @click="collapsed = !collapsed">
        <span class="blogo">DH</span>
        <span v-if="!collapsed" class="bname">DevHub</span>
        <el-icon v-if="!collapsed" style="margin-left:auto"><Fold /></el-icon>
        <el-icon v-else style="margin-left:auto"><Expand /></el-icon>
      </div>
      <el-menu :default-active="$route.path" :collapse="collapsed" router style="border-right: none">
        <template v-for="m in store.menuTree" :key="m.id">
          <el-sub-menu v-if="m.children && m.children.length" :index="String(m.id)">
            <template #title>{{ m.name }}</template>
            <el-menu-item v-for="k in m.children" :key="k.id" :index="k.path">{{ k.name }}</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.id === 6 ? '/ai/chat' : (m.path || '/dashboard')">
            {{ m.name }}
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="dh-topbar" style="display:flex; align-items:center; gap:12px">
        <span style="font-weight:600; font-size:15px; letter-spacing:.2px">{{ currentTitle }}</span>
        <GlobalSearch />
        <div style="margin-left:auto; display:flex; align-items:center; gap:14px">
          <NotificationBell />
          <el-switch v-model="dark" inline-prompt active-icon="Moon" inactive-icon="Sunny" @change="applyTheme" />
          <el-dropdown @command="onCmd">
            <span style="cursor:pointer; display:flex; align-items:center; gap:6px">
              <el-avatar :size="28" style="background:linear-gradient(135deg,#0071e3,#42a5f5); box-shadow:0 2px 8px rgba(0,113,227,.35)">{{ (store.username || '?')[0].toUpperCase() }}</el-avatar>
              {{ store.username }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main style="background: transparent; overflow-y: auto">
        <router-view v-slot="{ Component }">
          <keep-alive :include="['Dashboard', 'ProjectList', 'TaskList', 'DefectList', 'DocCenter', 'ReportPage', 'MetricsPage', 'SystemPage', 'ChatPage', 'HistoryPage', 'OpsPage']">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import NotificationBell from '../components/NotificationBell.vue'
import GlobalSearch from '../components/GlobalSearch.vue'

const store = useUserStore()
const route = useRoute(), router = useRouter()
const collapsed = ref(localStorage.getItem('dh_collapsed') === '1')
const dark = ref(localStorage.getItem('dh_theme') === 'dark')

const titleMap = {
  '/dashboard': '工作台', '/project': '项目管理', '/task': '任务中心', '/defect': '缺陷跟踪',
  '/doc': '文档中心', '/report': '报表统计', '/system': '系统管理',
  '/ai/chat': '智能问答 · 对话', '/ai/history': '智能问答 · 会话历史', '/ai/ops': '智能问答 · 知识运营'
}
const currentTitle = computed(() => titleMap[route.path] || 'DevHub')

function applyTheme(v) {
  document.documentElement.className = v ? 'dark' : ''
  localStorage.setItem('dh_theme', v ? 'dark' : 'light')
}
applyTheme(dark.value)

function onCmd(cmd) {
  if (cmd === 'logout') { store.logout(); router.push('/login') }
}
</script>

<style scoped>
.brand { display: flex; align-items: center; gap: 8px; padding: 14px 16px; cursor: pointer; }
.blogo { width: 30px; height: 30px; border-radius: 9px; background: linear-gradient(135deg, #0071e3, #42a5f5); color: #fff; font-weight: 800; font-size: 13px; line-height: 30px; text-align: center; flex-shrink: 0; box-shadow: 0 2px 8px rgba(0,113,227,.35); }
.bname { font-weight: 700; font-size: 15px; letter-spacing: .3px; }
</style>
