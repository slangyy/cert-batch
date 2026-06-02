<template>
  <div class="batch-generate">
    <h2>批量生成证书</h2>

    <el-steps :active="currentStep" finish-status="success" class="steps">
      <el-step title="选择模板" />
      <el-step title="上传数据" />
      <el-step title="确认生成" />
      <el-step title="完成" />
    </el-steps>

    <!-- Step 1: 选择模板 -->
    <div v-show="currentStep === 0" class="step-content">
      <div class="step-title">请选择证书模板</div>
      <div v-loading="loadingTemplates" class="template-select-list">
        <el-empty v-if="!loadingTemplates && templates.length === 0" description="暂无模板，请先创建模板" />
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="8" :lg="6" v-for="item in templates" :key="item.id">
            <el-card
              class="select-card"
              :class="{ selected: selectedTemplateId === item.id }"
              shadow="hover"
              @click="selectedTemplateId = item.id"
            >
              <div class="select-card-image">
                <img :src="getImageUrl(item.id)" :alt="item.name" />
              </div>
              <div class="select-card-name">{{ item.name }}</div>
              <el-icon v-if="selectedTemplateId === item.id" class="check-icon"><CircleCheckFilled /></el-icon>
            </el-card>
          </el-col>
        </el-row>
      </div>
      <div class="step-actions">
        <el-button type="primary" :disabled="!selectedTemplateId" @click="goStep(1)">下一步</el-button>
      </div>
    </div>

    <!-- Step 2: 上传Excel -->
    <div v-show="currentStep === 1" class="step-content">
      <div class="step-title">上传学生数据 Excel 文件</div>
      <el-upload
        drag
        :auto-upload="false"
        accept=".xlsx,.xls"
        :limit="1"
        :on-change="handleExcelChange"
        :on-remove="handleExcelRemove"
        :file-list="excelFileList"
        class="excel-upload"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 .xlsx 格式，第一行为列名（需与模板占位符名称一致）
          </div>
        </template>
      </el-upload>

      <!-- 数据预览 -->
      <div v-if="excelData.headers.length > 0" class="data-preview">
        <div class="preview-header">
          <span>数据预览（共 {{ excelData.rows.length }} 条）</span>
        </div>
        <el-table :data="excelData.rows.slice(0, 10)" border size="small" max-height="300" style="width: 100%">
          <el-table-column v-for="header in excelData.headers" :key="header" :prop="header" :label="header" min-width="120" />
        </el-table>
        <div v-if="excelData.rows.length > 10" class="preview-tip">仅显示前10条数据</div>

        <!-- 映射检查 -->
        <div class="mapping-check">
          <div class="mapping-title">占位符映射检查</div>
          <el-table :data="mappingStatus" border size="small" style="width: 100%">
            <el-table-column prop="placeholder" label="模板占位符" width="200" />
            <el-table-column prop="status" label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="row.matched ? 'success' : 'danger'" size="small">
                  {{ row.matched ? '已匹配' : '未匹配' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="excelColumn" label="对应Excel列" />
          </el-table>
        </div>
      </div>

      <div class="step-actions">
        <el-button @click="goStep(0)">上一步</el-button>
        <el-button type="primary" :disabled="excelData.rows.length === 0" @click="goStep(2)">下一步</el-button>
      </div>
    </div>

    <!-- Step 3: 确认生成 -->
    <div v-show="currentStep === 2" class="step-content">
      <div class="step-title">确认生成信息</div>

      <el-descriptions :column="1" border class="confirm-info">
        <el-descriptions-item label="证书模板">{{ selectedTemplateName }}</el-descriptions-item>
        <el-descriptions-item label="数据条数">{{ excelData.rows.length }} 条</el-descriptions-item>
        <el-descriptions-item label="输出格式">
          <el-radio-group v-model="outputFormat">
            <el-radio value="png">PNG 图片</el-radio>
            <el-radio value="pdf">PDF 文件</el-radio>
            <el-radio value="both">PNG + PDF</el-radio>
          </el-radio-group>
        </el-descriptions-item>
        <el-descriptions-item label="文件命名字段">
          <el-select v-model="fileNameField" placeholder="选择命名字段" clearable>
            <el-option v-for="h in excelData.headers" :key="h" :label="h" :value="h" />
          </el-select>
          <span class="field-tip">留空则使用序号命名</span>
        </el-descriptions-item>
        <el-descriptions-item label="输出目录">
          <div class="output-dir-row">
            <el-input v-model="outputDir" placeholder="请输入输出目录路径" />
            <el-button @click="selectOutputDir">浏览</el-button>
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="step-actions">
        <el-button @click="goStep(1)">上一步</el-button>
        <el-button type="primary" :loading="generating" @click="handleGenerate">
          <el-icon><Printer /></el-icon>
          开始生成
        </el-button>
      </div>
    </div>

    <!-- Step 4: 完成 -->
    <div v-show="currentStep === 3" class="step-content">
      <el-result
        :icon="generateResult.fail > 0 ? 'warning' : 'success'"
        :title="generateResult.fail > 0 ? '生成完成（部分失败）' : '生成完成'"
        :sub-title="`共 ${generateResult.total} 条，成功 ${generateResult.success} 条，失败 ${generateResult.fail} 条`"
      >
        <template #extra>
          <div v-if="generateResult.errors && generateResult.errors.length > 0" class="error-list">
            <el-alert
              v-for="(err, i) in generateResult.errors"
              :key="i"
              :title="err"
              type="error"
              :closable="false"
              show-icon
              style="margin-bottom: 8px;"
            />
          </div>
          <div class="result-actions">
            <el-button type="primary" @click="resetAll">继续生成</el-button>
          </div>
        </template>
      </el-result>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getTemplateList, getTemplateImageUrl, getPlaceholders, parseExcel, batchGenerate } from '@/api'

const currentStep = ref(0)
const loadingTemplates = ref(false)
const templates = ref([])
const selectedTemplateId = ref(null)
const templatePlaceholders = ref([])

const excelFileList = ref([])
const excelData = ref({ headers: [], rows: [] })

const outputFormat = ref('png')
const fileNameField = ref('')
const outputDir = ref('')
const generating = ref(false)
const generateResult = ref({ total: 0, success: 0, fail: 0, errors: [] })

const getImageUrl = (id) => getTemplateImageUrl(id)

const selectedTemplateName = computed(() => {
  const t = templates.value.find(t => t.id === selectedTemplateId.value)
  return t ? t.name : ''
})

const mappingStatus = computed(() => {
  return templatePlaceholders.value.map(ph => {
    const matched = excelData.value.headers.includes(ph.name)
    return {
      placeholder: ph.name,
      matched,
      excelColumn: matched ? ph.name : '-'
    }
  })
})

const loadTemplates = async () => {
  loadingTemplates.value = true
  try {
    const { data: res } = await getTemplateList()
    if (res.code === 200) {
      templates.value = res.data || []
    }
  } catch (e) {
    ElMessage.error('加载模板列表失败')
  } finally {
    loadingTemplates.value = false
  }
}

const goStep = async (step) => {
  // 进入step 1时加载占位符信息
  if (step === 1 && selectedTemplateId.value) {
    try {
      const { data: res } = await getPlaceholders(selectedTemplateId.value)
      if (res.code === 200) {
        templatePlaceholders.value = res.data || []
      }
    } catch (e) {
      // ignore
    }
  }
  currentStep.value = step
}

const handleExcelChange = async (file) => {
  excelFileList.value = [file]
  try {
    const { data: res } = await parseExcel(file.raw)
    if (res.code === 200) {
      excelData.value = res.data
      ElMessage.success(`解析成功，共 ${res.data.rows.length} 条数据`)
    } else {
      ElMessage.error(res.msg || '解析失败')
      excelData.value = { headers: [], rows: [] }
    }
  } catch (e) {
    ElMessage.error('解析Excel失败')
    excelData.value = { headers: [], rows: [] }
  }
}

const handleExcelRemove = () => {
  excelFileList.value = []
  excelData.value = { headers: [], rows: [] }
}

const handleGenerate = async () => {
  if (!outputDir.value.trim()) {
    return ElMessage.warning('请输入输出目录')
  }

  generating.value = true
  try {
    const { data: res } = await batchGenerate({
      templateId: selectedTemplateId.value,
      rows: excelData.value.rows,
      outputDir: outputDir.value.trim(),
      format: outputFormat.value,
      fileNameField: fileNameField.value || null
    })
    if (res.code === 200) {
      generateResult.value = res.data
      currentStep.value = 3
    } else {
      ElMessage.error(res.msg || '生成失败')
    }
  } catch (e) {
    ElMessage.error('生成失败: ' + (e.message || '网络错误'))
  } finally {
    generating.value = false
  }
}

const resetAll = () => {
  currentStep.value = 0
  selectedTemplateId.value = null
  excelFileList.value = []
  excelData.value = { headers: [], rows: [] }
  outputFormat.value = 'png'
  fileNameField.value = ''
  outputDir.value = ''
  generateResult.value = { total: 0, success: 0, fail: 0, errors: [] }
}

const selectOutputDir = async () => {
  // 优先使用 Electron 的目录选择
  if (window.electronAPI && window.electronAPI.selectDirectory) {
    const dir = await window.electronAPI.selectDirectory()
    if (dir) {
      outputDir.value = dir
    }
  } else {
    ElMessage.info('请直接输入目录路径（Electron 环境下可使用浏览按钮）')
  }
}

onMounted(() => {
  loadTemplates()
})
</script>

<style scoped>
.batch-generate {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  min-height: 100%;
}

.batch-generate h2 {
  margin: 0 0 24px 0;
  font-size: 20px;
  color: #303133;
}

.steps {
  margin-bottom: 32px;
}

.step-content {
  min-height: 400px;
}

.step-title {
  font-size: 16px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 16px;
}

.template-select-list {
  min-height: 200px;
}

.select-card {
  cursor: pointer;
  position: relative;
  margin-bottom: 16px;
  transition: all 0.2s;
}

.select-card.selected {
  border-color: #409EFF;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.2);
}

.select-card-image {
  height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  border-radius: 4px;
  overflow: hidden;
}

.select-card-image img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.select-card-name {
  text-align: center;
  margin-top: 8px;
  font-size: 14px;
  color: #303133;
}

.check-icon {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 24px;
  color: #409EFF;
}

.excel-upload {
  max-width: 500px;
  margin-bottom: 20px;
}

.data-preview {
  margin-top: 20px;
}

.preview-header {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
  color: #303133;
}

.preview-tip {
  text-align: center;
  color: #909399;
  font-size: 12px;
  padding: 8px 0;
}

.mapping-check {
  margin-top: 20px;
}

.mapping-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
  color: #303133;
}

.confirm-info {
  max-width: 700px;
  margin-bottom: 24px;
}

.output-dir-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.field-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.step-actions {
  margin-top: 24px;
  display: flex;
  gap: 12px;
}

.error-list {
  max-width: 600px;
  margin: 0 auto 16px;
  text-align: left;
}

.result-actions {
  margin-top: 16px;
}
</style>
