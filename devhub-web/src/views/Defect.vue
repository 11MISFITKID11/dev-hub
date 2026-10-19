<template>
  <div>
    <el-card shadow="never">
      <div class="table-toolbar">
        <el-select v-model="f.status" placeholder="状态：全部" clearable style="width:130px" @change="reset">
          <el-option v-for="o in store.dicts.defect_status || []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="f.severity" placeholder="严重级：全部" clearable style="width:130px" @change="reset">
          <el-option v-for="o in store.dicts.defect_severity || []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="openCreate">提缺陷</el-button>
      </div>
      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column label="项目" width="130"><template #default="s">{{ opts.projectName(s.row.projectId) }}</template></el-table-column>
        <el-table-column label="严重级" width="90"><template #default="s">
          <DictTag dict="defect_severity" :value="s.row.severity" />
        </template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="s">
          <DictTag dict="defect_status" :value="s.row.status" />
        </template></el-table-column>
        <el-table-column label="修复人" width="100"><template #default="s">{{ opts.userName(s.row.assigneeUsername) }}</template></el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="s">
            <el-button link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button link type="primary" @click="dlg = { ...s.row }; dlgV = true">编辑</el-button>
            <el-button link type="danger" @click="del(s.row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无缺陷">
            <el-button type="primary" @click="openCreate">提交第一个缺陷</el-button>
          </el-empty>
        </template>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next" :page-sizes="[10, 20, 50]" :total="total" v-model:current-page="page" v-model:page-size="size" @current-change="load" @size-change="reset" />
    </el-card>

    <el-dialog v-model="dlgV" :title="dlg.id ? '编辑缺陷' : '提缺陷'" width="460">
      <el-form ref="formRef" label-width="80px" :rules="rules" :model="dlg">
        <el-form-item label="标题" prop="title"><el-input v-model="dlg.title" /></el-form-item>
        <el-form-item label="项目" prop="projectId"><ProjectSelect v-model="dlg.projectId" width="100%" /></el-form-item>
        <el-form-item label="严重级"><el-select v-model="dlg.severity"><el-option v-for="o in store.dicts.defect_severity || []" :key="o.value" :label="o.label" :value="o.value" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="dlg.status"><el-option v-for="o in store.dicts.defect_status || []" :key="o.value" :label="o.label" :value="o.value" /></el-select></el-form-item>
        <el-form-item label="修复人"><UserSelect v-model="dlg.assigneeUsername" width="100%" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="dlg.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlgV = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确定</el-button></template>
    </el-dialog>

    <el-drawer v-model="dtlV" :title="'缺陷：' + (detail.title || '')" size="42%">
      <el-tabs>
        <el-tab-pane label="时间线">
          <el-timeline>
            <el-timeline-item v-for="t in timeline" :key="t.kind + t.id"
              :timestamp="(t.time || '').replace('T', ' ').slice(0, 16)" :type="t.kind === 'comment' ? 'primary' : ''">
              <div v-if="t.kind === 'comment'"><b>{{ t.actor }}</b>：{{ t.content }}</div>
              <div v-else><b>{{ t.actor }}</b>
                <span v-if="t.action === 'status'"> 状态 {{ t.oldValue }} → <el-tag size="small" type="success">{{ t.newValue }}</el-tag></span>
                <span v-else-if="t.action === 'create'"> 提交了缺陷</span>
                <span v-else> {{ t.action }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
          <el-input v-model="newComment" type="textarea" :rows="2" placeholder="评论…" />
          <el-button type="primary" size="small" style="margin-top:8px" @click="postComment">发表</el-button>
        </el-tab-pane>
        <el-tab-pane label="附件">
          <AttachmentList v-if="detail.id" obj-type="DEFECT" :obj-id="detail.id" />
        </el-tab-pane>
        <el-tab-pane label="相似缺陷">
          <div v-if="similarLoading" v-loading="true" style="height:120px"></div>
          <el-empty v-else-if="!similar.length" description="未找到相似的历史缺陷" :image-size="70" />
          <div v-for="s in similar" :key="s.id" class="sim-item" @click="openDetail({ id: s.id, title: s.title })">
            <div style="display:flex; align-items:center; gap:8px">
              <span style="font-weight:600; font-size:13px; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap">{{ s.title }}</span>
              <DictTag dict="defect_severity" :value="s.severity" />
              <DictTag dict="defect_status" :value="s.status" />
            </div>
            <div style="font-size:11px; color:var(--el-text-color-secondary); margin-top:4px">
              相似度 {{ Number(s.score || 0).toFixed(2) }} · #{{ s.id }} · 点击查看
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import api from '../api'
import { useCrudList } from '../composables/useCrudList'
import { useUserStore } from '../stores/user'
import { useOptionsStore } from '../stores/options'
import DictTag from '../components/DictTag.vue'
import UserSelect from '../components/UserSelect.vue'
import ProjectSelect from '../components/ProjectSelect.vue'
import AttachmentList from '../components/AttachmentList.vue'

defineOptions({ name: 'DefectList' })

const store = useUserStore(), opts = useOptionsStore()
const f = ref({})
const { rows, total, page, size, loading, load, reset } = useCrudList('/defects', () => f.value)
const formRef = ref(null), dlg = ref({}), dlgV = ref(false), saving = ref(false)
const rules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur' }],
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }]
}
const dtlV = ref(false), detail = ref({}), timeline = ref([]), newComment = ref('')
const similar = ref([]), similarLoading = ref(false)

function openCreate() { dlg.value = {}; dlgV.value = true }
async function openDetail(r) {
  detail.value = r; dtlV.value = true
  timeline.value = await api.get('/collab/DEFECT/' + r.id + '/timeline')
  // 相似缺陷（P2-8 语义检索，懒加载）
  similarLoading.value = true
  try {
    similar.value = (await api.get(`/ai-assist/defects/${r.id}/similar`)) || []
  } catch (e) { similar.value = [] } finally { similarLoading.value = false }
}
async function postComment() {
  if (!newComment.value.trim()) return
  await api.post('/collab/DEFECT/' + detail.value.id + '/comments', { content: newComment.value })
  newComment.value = ''; openDetail(detail.value)
}
async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (dlg.value.id) await api.put('/defects/' + dlg.value.id, dlg.value)
    else await api.post('/defects', dlg.value)
    dlgV.value = false; load()
  } finally { saving.value = false }
}
async function del(r) { await ElMessageBox.confirm(`删除缺陷「${r.title}」？`); await api.delete('/defects/' + r.id); load() }

onMounted(() => { load(); opts.load() })
</script>

<style scoped>
.sim-item {
  padding: 10px 12px; border-radius: 12px; margin-bottom: 8px; cursor: pointer;
  background: var(--dh-glass-bg); border: 1px solid var(--dh-hairline);
  transition: transform .15s ease, box-shadow .15s ease;
}
.sim-item:hover { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(0,0,0,.08); }
</style>
