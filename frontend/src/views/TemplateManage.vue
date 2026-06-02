<template>
  <div class="template-manage">
    <div class="page-header">
      <h2>模板管理</h2>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        新建模板
      </el-button>
    </div>

    <div class="template-list" v-loading="loading">
      <el-empty v-if="!loading && templates.length === 0" description="暂无模板，点击上方按钮创建" />

      <el-row :gutter="20">
        <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="item in templates" :key="item.id">
          <el-card class="template-card" shadow="hover">
            <div class="card-image" @click="editTemplate(item.id)">
              <img :src="getImageUrl(item.id)" :alt="item.name" />
            </div>
            <div class="card-info">
              <span class="card-name">{{ item.name }}</span>
              <span class="card-size">{{ item.imageWidth }} × {{ item.imageHeight }}</span>
            </div>
            <div class="card-actions">
              <el-button type="primary" text size="small" @click="editTemplate(item.id)">
                <el-icon><Edit /></el-icon>
                编辑
              </el-button>
              <el-button type="primary" text size="small" @click="renameTemplate(item)">
                <el-icon><EditPen /></el-icon>
                重命名
              </el-button>
              <el-button type="danger" text size="small" @click="handleDelete(item)">
                <el-icon><Delete /></el-icon>
                删除
              </el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <!-- 新建模板对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建模板" width="480px" :close-on-click-modal="false">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="模板名称" required>
          <el-input v-model="createForm.name" placeholder="请输入模板名称" />
        </el-form-item>
        <el-form-item label="模板图片" required>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            accept=".png,.jpg,.jpeg"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            list-type="picture"
          >
            <el-button type="primary">选择图片</el-button>
            <template #tip>
              <div class="el-upload__tip">支持 PNG / JPG 格式</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { getTemplateList, createTemplate, deleteTemplate, updateTemplateName, getTemplateImageUrl } from '@/api'

const router = useRouter()
const loading = ref(false)
const templates = ref([])
const createDialogVisible = ref(false)
const creating = ref(false)
const uploadRef = ref(null)
const createForm = ref({ name: '', imageFile: null })

const getImageUrl = (id) => getTemplateImageUrl(id)

const loadTemplates = async () => {
  loading.value = true
  try {
    const { data: res } = await getTemplateList()
    if (res.code === 200) {
      templates.value = res.data || []
    }
  } catch (e) {
    // 后端可能还没启动，静默处理，不弹错误提示
  } finally {
    loading.value = false
  }
}

const showCreateDialog = () => {
  createForm.value = { name: '', imageFile: null }
  createDialogVisible.value = true
}

const handleFileChange = (file) => {
  createForm.value.imageFile = file.raw
}

const handleFileRemove = () => {
  createForm.value.imageFile = null
}

const handleCreate = async () => {
  if (!createForm.value.name.trim()) {
    return ElMessage.warning('请输入模板名称')
  }
  if (!createForm.value.imageFile) {
    return ElMessage.warning('请选择模板图片')
  }

  creating.value = true
  try {
    const { data: res } = await createTemplate(createForm.value.name, createForm.value.imageFile)
    if (res.code === 200) {
      ElMessage.success('创建成功')
      createDialogVisible.value = false
      await loadTemplates()
      // 跳转到编辑器
      router.push(`/template/editor/${res.data.id}`)
    } else {
      ElMessage.error(res.msg || '创建失败')
    }
  } catch (e) {
    ElMessage.error('创建失败')
  } finally {
    creating.value = false
  }
}

const editTemplate = (id) => {
  router.push(`/template/editor/${id}`)
}

const renameTemplate = async (item) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名模板', {
      inputValue: item.name,
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    if (value && value.trim()) {
      await updateTemplateName(item.id, value.trim())
      ElMessage.success('重命名成功')
      await loadTemplates()
    }
  } catch {
    // 取消
  }
}

const handleDelete = async (item) => {
  try {
    await ElMessageBox.confirm(`确定删除模板「${item.name}」吗？此操作不可恢复。`, '提示', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    await deleteTemplate(item.id)
    ElMessage.success('删除成功')
    await loadTemplates()
  } catch {
    // 取消
  }
}

onMounted(() => {
  loadTemplates()
})
</script>

<style scoped>
.template-manage {
  height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  color: #303133;
}

.template-card {
  margin-bottom: 20px;
}

.card-image {
  height: 180px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  cursor: pointer;
  border-radius: 4px;
}

.card-image img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.card-info {
  padding: 10px 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-name {
  font-weight: 500;
  color: #303133;
  font-size: 14px;
}

.card-size {
  color: #909399;
  font-size: 12px;
}

.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  border-top: 1px solid #eee;
  padding-top: 8px;
}
</style>
