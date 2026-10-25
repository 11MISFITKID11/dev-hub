<template>
  <el-dialog v-model="show" title="个人资料" width="430px" @open="load">
    <el-descriptions :column="1" border size="small" style="margin-bottom:16px">
      <el-descriptions-item label="用户名">{{ p.username || '…' }}</el-descriptions-item>
      <el-descriptions-item label="角色">
        <el-tag v-for="r in (p.roles || [])" :key="r" size="small" style="margin-right:6px"
                :type="r === 'ADMIN' ? 'danger' : 'info'">{{ r === 'ADMIN' ? '管理员' : (r === 'USER' ? '客户' : r) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="注册时间">{{ (p.createTime || '').replace('T', ' ').slice(0, 16) }}</el-descriptions-item>
    </el-descriptions>

    <el-divider content-position="left">昵称</el-divider>
    <div style="display:flex; gap:8px; margin-bottom:6px">
      <el-input v-model="nickname" placeholder="设置昵称" maxlength="32" style="width:220px" />
      <el-button :loading="savingNick" @click="saveNick">保存昵称</el-button>
    </div>

    <el-divider content-position="left">修改密码</el-divider>
    <el-form label-width="64px" @submit.prevent="changePwd">
      <el-form-item label="原密码"><el-input v-model="pwd.oldPassword" type="password" show-password /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 6 位" /></el-form-item>
      <el-form-item label="确认"><el-input v-model="pwd.confirm" type="password" show-password @keyup.enter="changePwd" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="show = false">关闭</el-button>
      <el-button type="primary" :loading="savingPwd" @click="changePwd">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import api from '../api'

const show = defineModel({ type: Boolean, default: false })
const store = useUserStore(), router = useRouter()
const p = ref({}), nickname = ref('')
const pwd = ref({ oldPassword: '', newPassword: '', confirm: '' })
const savingNick = ref(false), savingPwd = ref(false)

async function load() {
  try {
    p.value = await api.get('/system/profile')
    nickname.value = p.value.nickname || ''
  } catch (e) { /* 拦截器弹错 */ }
  pwd.value = { oldPassword: '', newPassword: '', confirm: '' }
}
async function saveNick() {
  savingNick.value = true
  try {
    await api.put('/system/profile', { nickname: nickname.value })
    ElMessage.success('昵称已保存')
  } finally { savingNick.value = false }
}
async function changePwd() {
  const f = pwd.value
  if (!f.oldPassword || !f.newPassword) { ElMessage.warning('请填写原密码与新密码'); return }
  if (f.newPassword.length < 6) { ElMessage.warning('新密码至少 6 位'); return }
  if (f.newPassword !== f.confirm) { ElMessage.warning('两次输入的新密码不一致'); return }
  savingPwd.value = true
  try {
    await api.post('/system/profile/password', { oldPassword: f.oldPassword, newPassword: f.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    show.value = false
    store.logout()
    router.push('/login')
  } finally { savingPwd.value = false }
}
</script>
