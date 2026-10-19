<template>
  <div style="display:grid; grid-template-columns:1fr 1fr; gap:16px">
    <el-card shadow="never">
      <template #header>用户管理</template>
      <div style="display:flex; gap:8px; margin-bottom:12px">
        <el-input v-model="ukw" placeholder="用户名搜索" style="width:180px" @keyup.enter="loadUsers" />
        <el-button type="primary" @click="uDlg = true; uForm = { roleKey: 'USER' }">新建用户</el-button>
        <el-button @click="pwdDlg = true">修改我的密码</el-button>
      </div>
      <el-table :data="users" size="small">
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="dept_name" label="部门" width="90" />
        <el-table-column prop="roles" label="角色" width="90" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="s">
            <el-button link type="primary" @click="rpUser = s.row.username; rpDlg = true">重置密码</el-button>
            <el-button link type="danger" :disabled="s.row.username === 'admin'" @click="delUser(s.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>角色权限</template>
      <div v-for="r in roles" :key="r.id" style="display:flex; justify-content:space-between; align-items:center; padding:8px 0; border-bottom:1px solid var(--el-border-color-lighter); font-size:13px">
        <span><b>{{ r.role_key }}</b> · {{ r.name }}（{{ r.menu_count }} 项菜单权限）</span>
        <el-button link type="primary" @click="cfgRole(r)">配置权限</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>部门树</template>
      <el-tree :data="deptTree" :props="{ label: 'name', children: 'children' }" default-expand-all />
    </el-card>

    <el-card shadow="never">
      <template #header>字典</template>
      <div v-for="(vs, k) in store.dicts" :key="k" style="font-size:12.5px; margin:6px 0">
        <b>{{ k }}</b>：{{ vs.map(v => v.label).join(' / ') }}
      </div>
    </el-card>

    <el-card shadow="never" style="grid-column:1/-1">
      <template #header>最近操作日志</template>
      <el-table :data="opLogs" size="small">
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="action" label="动作" width="110" />
        <el-table-column prop="username" label="操作人" width="100" />
        <el-table-column prop="costMs" label="耗时" width="90"><template #default="s">{{ s.row.costMs }}ms</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="s"><el-tag :type="s.row.status === 1 ? 'success' : 'danger'" size="small">{{ s.row.status === 1 ? '成功' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column prop="errorMsg" label="错误" min-width="120" />
      </el-table>
    </el-card>
  </div>

  <!-- 新建用户 -->
  <el-dialog v-model="uDlg" title="新建用户" width="400">
    <el-form ref="uRef" label-width="70px" :rules="uRules" :model="uForm">
      <el-form-item label="用户名" prop="username"><el-input v-model="uForm.username" /></el-form-item>
      <el-form-item label="初始密码" prop="password"><el-input v-model="uForm.password" type="password" show-password /></el-form-item>
      <el-form-item label="昵称" prop="nickname"><el-input v-model="uForm.nickname" /></el-form-item>
      <el-form-item label="角色"><el-select v-model="uForm.roleKey"><el-option label="普通用户" value="USER" /><el-option label="管理员" value="ADMIN" /></el-select></el-form-item>
    </el-form>
    <template #footer><el-button @click="uDlg = false">取消</el-button><el-button type="primary" :loading="saving" @click="addUser">确定</el-button></template>
  </el-dialog>

  <!-- 重置密码 -->
  <el-dialog v-model="rpDlg" :title="`重置密码：${rpUser}`" width="360">
    <el-input v-model="rpPwd" placeholder="新密码（≥6位）" type="password" show-password />
    <template #footer><el-button @click="rpDlg = false">取消</el-button><el-button type="primary" :loading="saving" @click="resetPwd">确定</el-button></template>
  </el-dialog>

  <!-- 修改我的密码 -->
  <el-dialog v-model="pwdDlg" title="修改我的密码" width="380">
    <el-input v-model="pwd.oldPassword" placeholder="原密码" type="password" show-password style="margin-bottom:10px" />
    <el-input v-model="pwd.newPassword" placeholder="新密码（≥6位）" type="password" show-password />
    <template #footer><el-button @click="pwdDlg = false">取消</el-button><el-button type="primary" :loading="saving" @click="changePwd">确定</el-button></template>
  </el-dialog>

  <!-- 角色权限勾选 -->
  <el-dialog v-model="permDlg" title="配置角色权限" width="420">
    <div v-for="m in allMenus" :key="m.id" style="padding:4px 0; font-size:13px">
      <el-checkbox v-model="m.checked" :label="(m.parent_id ? '└ ' : '') + m.name + (m.perm ? ` (${m.perm})` : '')" />
    </div>
    <template #footer><el-button @click="permDlg = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="savePerm">保存</el-button></template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import api from '../api'
import { useUserStore } from '../stores/user'

defineOptions({ name: 'SystemPage' })

const store = useUserStore(), router = useRouter()
const users = ref([]), ukw = ref(''), roles = ref([]), deptTree = ref([]), opLogs = ref([])
const uDlg = ref(false), uForm = ref({}), rpDlg = ref(false), rpUser = ref(''), rpPwd = ref('')
const pwdDlg = ref(false), pwd = ref({})
const permDlg = ref(false), allMenus = ref([]), permRole = ref(null)
const uRef = ref(null), saving = ref(false)
const uRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' }, { min: 6, message: '至少 6 位', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }]
}

async function loadUsers() {
  try {
    const d = await api.get('/system/users', { params: { size: 50, keyword: ukw.value || undefined } })
    users.value = d.records || []
  } catch (e) { users.value = [] }
}
async function addUser() {
  await uRef.value.validate()
  saving.value = true
  try { await api.post('/system/users', uForm.value); uDlg.value = false; loadUsers() }
  finally { saving.value = false }
}
async function delUser(r) {
  await ElMessageBox.confirm(`删除用户 ${r.username}？`)
  await api.delete(`/system/users/${r.username}`); loadUsers()
}
async function resetPwd() {
  if (!rpPwd.value || rpPwd.value.length < 6) { ElMessage.warning('新密码至少 6 位'); return }
  saving.value = true
  try {
    await api.post(`/system/users/${rpUser.value}/resetPwd`, { password: rpPwd.value })
    rpDlg.value = false; rpPwd.value = ''; ElMessage.success('已重置')
  } finally { saving.value = false }
}
async function changePwd() {
  saving.value = true
  try {
    await api.post('/system/profile/password', pwd.value)
    ElMessage.success('密码已修改，请重新登录')
    store.logout(); router.push('/login')
  } finally { saving.value = false }
}
async function cfgRole(r) {
  permRole.value = r
  const mine = new Set(await api.get(`/system/roles/${r.id}/menus`))
  allMenus.value = (await api.get('/system/menus/all')).map(m => ({ ...m, checked: mine.has(m.id) }))
  permDlg.value = true
}
async function savePerm() {
  const ids = allMenus.value.filter(m => m.checked).map(m => m.id)
  await api.put(`/system/roles/${permRole.value.id}/menus`, { menuIds: ids })
  permDlg.value = false; ElMessage.success('已保存（下次登录生效）'); loadAll()
}
async function loadAll() {
  loadUsers()
  try { roles.value = await api.get('/system/roles') } catch (e) {}
  try { deptTree.value = await api.get('/system/depts') } catch (e) {}
  try { opLogs.value = (await api.get('/system/logs/oper', { params: { size: 8 } })).records || [] } catch (e) {}
}
onMounted(loadAll)
</script>
