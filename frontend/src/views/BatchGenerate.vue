<template>
  <div class="batch-generate">
    <h2>批量生成证书</h2>

    <el-steps :active="currentStep" finish-status="success" class="steps">
      <el-step title="选择模板" />
      <el-step title="上传数据" />
      <el-step title="配置批次" />
      <el-step title="批次队列" />
    </el-steps>

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
        <el-button type="primary" :disabled="!selectedTemplateId || queueRunning" @click="goStep(1)">下一步</el-button>
      </div>
    </div>

    <div v-show="currentStep === 1" class="step-content">
      <div class="step-title">上传本批次 Excel 数据</div>
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
          <div class="el-upload__tip">支持 .xlsx/.xls，第一行为列名，需与模板占位符名称一致</div>
        </template>
      </el-upload>

      <div v-if="excelData.headers.length > 0" class="data-preview">
        <div class="preview-header">
          <span>数据预览，共 {{ excelData.totalRows }} 条</span>
        </div>
        <el-table :data="excelData.rows.slice(0, 10)" border size="small" max-height="300" style="width: 100%">
          <el-table-column v-for="header in excelData.headers" :key="header" :prop="header" :label="header" min-width="120" />
        </el-table>
        <div v-if="excelData.totalRows > 10" class="preview-tip">仅显示前 10 条数据</div>

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
            <el-table-column prop="excelColumn" label="对应 Excel 列" />
          </el-table>
        </div>
      </div>

      <div class="step-actions">
        <el-button :disabled="queueRunning" @click="goStep(0)">上一步</el-button>
        <el-button type="primary" :disabled="excelData.totalRows === 0 || queueRunning" @click="goStep(2)">下一步</el-button>
      </div>
    </div>

    <div v-show="currentStep === 2" class="step-content">
      <div class="step-title">配置当前批次</div>

      <el-descriptions :column="1" border class="confirm-info">
        <el-descriptions-item label="批次名称">
          <el-input v-model="batchName" placeholder="用于创建输出子目录" />
        </el-descriptions-item>
        <el-descriptions-item label="生成模式">
          <el-radio-group v-model="generateMode">
            <el-radio value="normal">普通生成</el-radio>
            <el-radio value="miniProgram">生成上传小程序模板</el-radio>
          </el-radio-group>
        </el-descriptions-item>
        <el-descriptions-item label="证书模板">{{ selectedTemplateName }}</el-descriptions-item>
        <el-descriptions-item label="数据条数">{{ excelData.totalRows }} 条</el-descriptions-item>
        <el-descriptions-item label="输出格式">
          <template v-if="generateMode === 'normal'">
            <el-radio-group v-model="outputFormat">
              <el-radio value="jpg">JPG 图片</el-radio>
              <el-radio value="png">PNG 图片</el-radio>
              <el-radio value="pdf">PDF 文件</el-radio>
              <el-radio value="both">JPG + PDF</el-radio>
            </el-radio-group>
          </template>
          <span v-else>固定输出 JPG，并打包为小程序上传 ZIP</span>
        </el-descriptions-item>
        <el-descriptions-item label="文件命名字段">
          <el-select v-model="fileNameField" placeholder="选择命名字段" clearable>
            <el-option v-for="h in excelData.headers" :key="h" :label="h" :value="h" />
          </el-select>
          <span class="field-tip">留空则使用序号命名</span>
        </el-descriptions-item>
        <el-descriptions-item label="输出目录">
          <div v-if="generateMode === 'miniProgram'" class="mini-program-options">
            <el-upload
              :auto-upload="false"
              accept=".xlsx,.xls"
              :limit="1"
              :on-change="handleListTemplateChange"
              :on-remove="handleListTemplateRemove"
              :file-list="listTemplateFileList"
            >
              <el-button>上传 list.xlsx 模板</el-button>
            </el-upload>
            <el-select v-model="miniProgramGuidColumn" placeholder="选择 GUID 写入列" class="mini-program-control">
              <el-option v-for="h in listTemplateData.headers" :key="h" :label="h" :value="h" />
            </el-select>
            <el-input v-model="certificateFolderName" placeholder="证书图片文件夹名称" class="mini-program-control" />
          </div>
          <div class="output-dir-row">
            <el-input v-model="outputDir" placeholder="请选择本批次基础输出目录" />
            <el-button @click="selectOutputDir">浏览</el-button>
          </div>
          <div class="final-dir">最终输出目录：{{ currentFinalOutputDir || '-' }}</div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="step-actions">
        <el-button :disabled="queueRunning" @click="goStep(1)">上一步</el-button>
        <el-button :disabled="queueRunning" @click="addCurrentBatch">加入批次</el-button>
        <el-button type="primary" :loading="queueRunning" @click="addCurrentBatchAndStart">
          加入并开始全部生成
        </el-button>
      </div>
    </div>

    <div v-show="currentStep === 3" class="step-content">
      <div class="step-title">批次队列</div>
      <el-empty v-if="batchQueue.length === 0" description="暂无批次，请先添加批次" />
    </div>

    <div v-if="batchQueue.length > 0" class="queue-panel">
      <div class="queue-header">
        <div>
          <div class="queue-title">待生成批次</div>
          <div class="queue-summary">
            共 {{ batchQueue.length }} 批，成功 {{ queueSummary.success }} 批，失败 {{ queueSummary.failed }} 批
          </div>
        </div>
        <div class="queue-actions">
          <el-button :disabled="queueRunning" @click="prepareNewBatch">继续添加批次</el-button>
          <el-button type="primary" :loading="queueRunning" :disabled="!hasRunnableBatch" @click="startBatchQueue">
            开始全部生成
          </el-button>
        </div>
      </div>

      <el-table :data="batchQueue" border size="small" style="width: 100%">
        <el-table-column prop="name" label="批次名称" min-width="140" />
        <el-table-column prop="templateName" label="模板" min-width="130" />
        <el-table-column label="模式" width="120">
          <template #default="{ row }">{{ row.generateMode === 'miniProgram' ? '小程序 ZIP' : '普通生成' }}</template>
        </el-table-column>
        <el-table-column label="数据" width="90">
          <template #default="{ row }">{{ row.dataSummary.totalRows }} 条</template>
        </el-table-column>
        <el-table-column prop="finalOutputDir" label="最终输出目录" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" size="small">{{ statusMeta(row.status).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="row.progress.percent" :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.status !== 'pending' || queueRunning" @click="editBatch(row)">编辑</el-button>
            <el-button link type="danger" :disabled="row.status !== 'pending' || queueRunning" @click="removeBatch(row.id)">删除</el-button>
            <el-button link type="primary" :disabled="row.status === 'pending'" @click="openBatchOutputDir(row)">打开目录</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="queueFinished" class="queue-result">
        <el-alert
          :type="queueSummary.failed > 0 ? 'warning' : 'success'"
          :title="`生成结束：成功 ${queueSummary.success} 批，失败 ${queueSummary.failed} 批`"
          :closable="false"
          show-icon
        />
        <div v-for="batch in finishedBatches" :key="batch.id" class="batch-result-line">
          <span>{{ batch.name }}</span>
          <span>{{ batch.status === 'success' ? '完成' : batch.error }}</span>
          <span v-if="batch.result?.zipFiles?.length">ZIP：{{ batch.result.zipFiles.map(file => `${file.name} (${formatFileSize(file.size)})`).join('，') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getTemplateList,
  getTemplateImageUrl,
  getPlaceholders,
  parseExcel,
  batchGenerateFileSSEPromise,
  batchGenerateMiniProgramZipSSEPromise
} from '@/api'

const currentStep = ref(0)
const loadingTemplates = ref(false)
const templates = ref([])
const selectedTemplateId = ref(null)
const templatePlaceholders = ref([])

const batchName = ref('')
const excelFileList = ref([])
const excelRawFile = ref(null)
const excelData = ref({ headers: [], rows: [], totalRows: 0 })

const generateMode = ref('normal')
const listTemplateFileList = ref([])
const listTemplateRawFile = ref(null)
const listTemplateData = ref({ headers: [], rows: [], totalRows: 0 })
const miniProgramGuidColumn = ref('')
const certificateFolderName = ref('社会实践活动证书')

const outputFormat = ref('jpg')
const fileNameField = ref('')
const outputDir = ref('')
const batchQueue = ref([])
const queueRunning = ref(false)
const queueFinished = ref(false)

const emptyProgress = () => ({ current: 0, total: 0, success: 0, fail: 0, percent: 0 })
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

const currentFinalOutputDir = computed(() => {
  if (!outputDir.value.trim()) return ''
  return buildFinalOutputDir(outputDir.value, batchName.value || defaultBatchName())
})

const hasRunnableBatch = computed(() => batchQueue.value.some(batch => batch.status === 'pending'))
const finishedBatches = computed(() => batchQueue.value.filter(batch => batch.status === 'success' || batch.status === 'failed'))
const queueSummary = computed(() => ({
  success: batchQueue.value.filter(batch => batch.status === 'success').length,
  failed: batchQueue.value.filter(batch => batch.status === 'failed').length
}))

const loadTemplates = async () => {
  loadingTemplates.value = true
  try {
    const { data: res } = await getTemplateList()
    if (res.code === 200) {
      templates.value = res.data || []
    }
  } catch (e) {
    // 后端可能还没启动，静默处理
  } finally {
    loadingTemplates.value = false
  }
}

const goStep = async (step) => {
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
  excelRawFile.value = file.raw
  if (!batchName.value.trim()) {
    batchName.value = fileBaseName(file.name)
  }
  try {
    const { data: res } = await parseExcel(file.raw)
    if (res.code === 200) {
      excelData.value = {
        headers: res.data.headers || [],
        rows: res.data.rows || res.data.previewRows || [],
        totalRows: res.data.totalRows || 0
      }
      ElMessage.success(`解析成功，共 ${excelData.value.totalRows} 条数据`)
    } else {
      ElMessage.error(res.msg || '解析失败')
      handleExcelRemove()
    }
  } catch (e) {
    ElMessage.error('解析 Excel 失败')
    handleExcelRemove()
  }
}

const handleExcelRemove = () => {
  excelFileList.value = []
  excelRawFile.value = null
  excelData.value = { headers: [], rows: [], totalRows: 0 }
}

const handleListTemplateChange = async (file) => {
  listTemplateFileList.value = [file]
  listTemplateRawFile.value = file.raw
  miniProgramGuidColumn.value = ''
  try {
    const { data: res } = await parseExcel(file.raw)
    if (res.code === 200) {
      listTemplateData.value = {
        headers: res.data.headers || [],
        rows: res.data.rows || res.data.previewRows || [],
        totalRows: res.data.totalRows || 0
      }
      if (listTemplateData.value.headers.length > 0) {
        miniProgramGuidColumn.value = listTemplateData.value.headers[0]
      }
      ElMessage.success('list.xlsx 模板解析成功')
    } else {
      ElMessage.error(res.msg || 'list.xlsx 模板解析失败')
      handleListTemplateRemove()
    }
  } catch (e) {
    ElMessage.error('list.xlsx 模板解析失败')
    handleListTemplateRemove()
  }
}

const handleListTemplateRemove = () => {
  listTemplateFileList.value = []
  listTemplateRawFile.value = null
  listTemplateData.value = { headers: [], rows: [], totalRows: 0 }
  miniProgramGuidColumn.value = ''
}

const validateCurrentBatch = () => {
  if (!selectedTemplateId.value) {
    ElMessage.warning('请选择证书模板')
    currentStep.value = 0
    return false
  }
  if (!excelRawFile.value) {
    ElMessage.warning('请先上传 Excel 文件')
    currentStep.value = 1
    return false
  }
  if (!batchName.value.trim()) {
    ElMessage.warning('请输入批次名称')
    return false
  }
  if (!outputDir.value.trim()) {
    ElMessage.warning('请选择本批次输出目录')
    return false
  }
  if (generateMode.value === 'miniProgram') {
    if (!listTemplateRawFile.value) {
      ElMessage.warning('请上传 list.xlsx 模板')
      return false
    }
    if (!miniProgramGuidColumn.value) {
      ElMessage.warning('请选择 GUID 写入列')
      return false
    }
    if (!certificateFolderName.value.trim()) {
      ElMessage.warning('请输入证书图片文件夹名称')
      return false
    }
  }
  return true
}

const makeCurrentBatch = () => {
  const name = sanitizeFileName(batchName.value)
  const finalOutputDir = buildFinalOutputDir(outputDir.value, name)
  return {
    id: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
    name,
    templateId: selectedTemplateId.value,
    templateName: selectedTemplateName.value,
    dataFile: excelRawFile.value,
    dataFileList: [...excelFileList.value],
    dataSummary: {
      headers: [...excelData.value.headers],
      rows: [...excelData.value.rows],
      totalRows: excelData.value.totalRows
    },
    generateMode: generateMode.value,
    format: outputFormat.value,
    fileNameField: fileNameField.value || null,
    baseOutputDir: outputDir.value.trim(),
    finalOutputDir,
    miniProgramConfig: {
      listTemplateFile: listTemplateRawFile.value,
      listTemplateFileList: [...listTemplateFileList.value],
      listTemplateData: {
        headers: [...listTemplateData.value.headers],
        rows: [...listTemplateData.value.rows],
        totalRows: listTemplateData.value.totalRows
      },
      guidColumn: miniProgramGuidColumn.value,
      certificateFolderName: certificateFolderName.value.trim()
    },
    status: 'pending',
    progress: { ...emptyProgress(), total: excelData.value.totalRows },
    result: null,
    error: ''
  }
}

const addCurrentBatch = () => {
  if (!validateCurrentBatch()) return false
  const batch = makeCurrentBatch()
  const duplicate = batchQueue.value.find(item => normalizePath(item.finalOutputDir) === normalizePath(batch.finalOutputDir))
  if (duplicate) {
    ElMessage.warning(`最终输出目录与批次“${duplicate.name}”重复，请修改批次名称或输出目录`)
    return false
  }
  batchQueue.value.push(batch)
  queueFinished.value = false
  ElMessage.success(`已加入批次：${batch.name}`)
  clearCurrentBatchForm()
  currentStep.value = 3
  return true
}

const addCurrentBatchAndStart = async () => {
  const added = addCurrentBatch()
  if (added) {
    await startBatchQueue()
  }
}

const prepareNewBatch = () => {
  clearCurrentBatchForm()
  currentStep.value = 0
}

const editBatch = async (batch) => {
  if (batch.status !== 'pending' || queueRunning.value) return
  batchQueue.value = batchQueue.value.filter(item => item.id !== batch.id)
  selectedTemplateId.value = batch.templateId
  templatePlaceholders.value = []
  batchName.value = batch.name
  excelRawFile.value = batch.dataFile
  excelFileList.value = [...batch.dataFileList]
  excelData.value = {
    headers: [...batch.dataSummary.headers],
    rows: [...batch.dataSummary.rows],
    totalRows: batch.dataSummary.totalRows
  }
  generateMode.value = batch.generateMode
  outputFormat.value = batch.format
  fileNameField.value = batch.fileNameField || ''
  outputDir.value = batch.baseOutputDir
  listTemplateRawFile.value = batch.miniProgramConfig.listTemplateFile
  listTemplateFileList.value = [...batch.miniProgramConfig.listTemplateFileList]
  listTemplateData.value = {
    headers: [...batch.miniProgramConfig.listTemplateData.headers],
    rows: [...batch.miniProgramConfig.listTemplateData.rows],
    totalRows: batch.miniProgramConfig.listTemplateData.totalRows
  }
  miniProgramGuidColumn.value = batch.miniProgramConfig.guidColumn
  certificateFolderName.value = batch.miniProgramConfig.certificateFolderName || '社会实践活动证书'
  await goStep(1)
  currentStep.value = 2
}

const removeBatch = (id) => {
  if (queueRunning.value) return
  batchQueue.value = batchQueue.value.filter(batch => batch.id !== id || batch.status !== 'pending')
}

const startBatchQueue = async () => {
  if (queueRunning.value) return
  const runnable = batchQueue.value.filter(batch => batch.status === 'pending')
  if (runnable.length === 0) {
    ElMessage.warning('没有待生成批次')
    return
  }
  if (!validateUniqueOutputDirs()) return

  queueRunning.value = true
  queueFinished.value = false
  currentStep.value = 3

  for (const batch of batchQueue.value) {
    if (batch.status !== 'pending') continue
    batch.status = 'running'
    batch.error = ''
    batch.result = null
    batch.progress = { ...emptyProgress(), total: batch.dataSummary.totalRows }
    try {
      const result = await runBatch(batch)
      batch.result = result
      batch.status = 'success'
      batch.progress.percent = 100
    } catch (error) {
      batch.status = 'failed'
      batch.error = error.message || '生成失败'
    }
  }

  queueRunning.value = false
  queueFinished.value = true
  if (queueSummary.value.failed > 0) {
    ElMessage.warning(`生成结束，${queueSummary.value.failed} 个批次失败`)
  } else {
    ElMessage.success('全部批次生成完成')
  }
}

const runBatch = (batch) => {
  const onProgress = (progress) => {
    batch.progress = {
      current: progress.current || 0,
      total: progress.total || batch.dataSummary.totalRows,
      success: progress.success || 0,
      fail: progress.fail || 0,
      percent: progress.percent || 0
    }
  }

  if (batch.generateMode === 'miniProgram') {
    return batchGenerateMiniProgramZipSSEPromise(
      {
        templateId: batch.templateId,
        dataFile: batch.dataFile,
        listTemplateFile: batch.miniProgramConfig.listTemplateFile,
        outputDir: batch.finalOutputDir,
        guidColumn: batch.miniProgramConfig.guidColumn,
        certificateFolderName: batch.miniProgramConfig.certificateFolderName
      },
      onProgress
    )
  }

  return batchGenerateFileSSEPromise(
    {
      templateId: batch.templateId,
      file: batch.dataFile,
      outputDir: batch.finalOutputDir,
      format: batch.format,
      fileNameField: batch.fileNameField
    },
    onProgress
  )
}

const validateUniqueOutputDirs = () => {
  const seen = new Map()
  for (const batch of batchQueue.value) {
    const normalized = normalizePath(batch.finalOutputDir)
    if (seen.has(normalized)) {
      ElMessage.error(`批次“${seen.get(normalized)}”和“${batch.name}”的最终输出目录重复`)
      return false
    }
    seen.set(normalized, batch.name)
  }
  return true
}

const clearCurrentBatchForm = () => {
  selectedTemplateId.value = null
  templatePlaceholders.value = []
  batchName.value = ''
  excelFileList.value = []
  excelRawFile.value = null
  excelData.value = { headers: [], rows: [], totalRows: 0 }
  generateMode.value = 'normal'
  listTemplateFileList.value = []
  listTemplateRawFile.value = null
  listTemplateData.value = { headers: [], rows: [], totalRows: 0 }
  miniProgramGuidColumn.value = ''
  certificateFolderName.value = '社会实践活动证书'
  outputFormat.value = 'jpg'
  fileNameField.value = ''
  outputDir.value = ''
}

const defaultBatchName = () => {
  if (batchName.value.trim()) return batchName.value
  const fileName = excelFileList.value[0]?.name || selectedTemplateName.value || `批次${batchQueue.value.length + 1}`
  return fileBaseName(fileName)
}

const fileBaseName = (name) => {
  return sanitizeFileName(String(name || '').replace(/\.[^.]+$/, ''))
}

const sanitizeFileName = (name) => {
  const sanitized = String(name || '')
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/[\s.]+$/g, '')
    .trim()
  return sanitized || `批次${batchQueue.value.length + 1}`
}

const buildFinalOutputDir = (baseDir, name) => {
  const base = baseDir.trim().replace(/[\\/]+$/g, '')
  const separator = base.includes('/') && !base.includes('\\') ? '/' : '\\'
  return `${base}${separator}${sanitizeFileName(name)}`
}

const normalizePath = (path) => String(path || '').replace(/[\\/]+/g, '\\').replace(/\\+$/g, '').toLowerCase()

const statusMeta = (status) => {
  const map = {
    pending: { text: '待生成', type: 'info' },
    running: { text: '生成中', type: 'warning' },
    success: { text: '已完成', type: 'success' },
    failed: { text: '失败', type: 'danger' }
  }
  return map[status] || map.pending
}

const formatFileSize = (size) => {
  const bytes = Number(size || 0)
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(2)} KB`
  }
  return `${bytes} B`
}

const openBatchOutputDir = async (batch) => {
  if (window.electronAPI?.openPath) {
    await window.electronAPI.openPath(batch.finalOutputDir)
  }
}

const selectOutputDir = async () => {
  if (window.electronAPI && window.electronAPI.selectDirectory) {
    const dir = await window.electronAPI.selectDirectory()
    if (dir) {
      outputDir.value = dir
    }
  } else {
    ElMessage.info('请直接输入目录路径，Electron 环境下可使用浏览按钮')
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
  min-height: 320px;
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

.preview-header,
.mapping-title {
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

.confirm-info {
  max-width: 760px;
  margin-bottom: 24px;
}

.output-dir-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.final-dir {
  margin-top: 8px;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}

.field-tip {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.mini-program-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}

.mini-program-control {
  max-width: 360px;
}

.step-actions {
  margin-top: 24px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.queue-panel {
  margin-top: 28px;
  border-top: 1px solid #ebeef5;
  padding-top: 20px;
}

.queue-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
}

.queue-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.queue-summary {
  margin-top: 4px;
  font-size: 13px;
  color: #606266;
}

.queue-actions {
  display: flex;
  gap: 10px;
}

.queue-result {
  margin-top: 16px;
}

.batch-result-line {
  display: grid;
  grid-template-columns: minmax(120px, 180px) minmax(120px, 1fr) minmax(180px, 2fr);
  gap: 12px;
  padding: 8px 0;
  font-size: 13px;
  color: #606266;
  border-bottom: 1px solid #f2f3f5;
}

@media (max-width: 768px) {
  .queue-header,
  .output-dir-row {
    align-items: stretch;
    flex-direction: column;
  }

  .queue-actions {
    flex-wrap: wrap;
  }

  .batch-result-line {
    display: block;
  }
}
</style>
