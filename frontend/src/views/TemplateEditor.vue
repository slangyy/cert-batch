<template>
  <div class="template-editor">
    <div class="editor-header">
      <div class="header-left">
        <el-button @click="goBack">
          <el-icon><ArrowLeft /></el-icon>
          返回
        </el-button>
        <span class="template-name">{{ template?.name || '加载中...' }}</span>
      </div>
      <div class="header-right">
        <el-button type="primary" @click="handleSave" :loading="saving">
          <el-icon><Check /></el-icon>
          保存
        </el-button>
      </div>
    </div>

    <div class="editor-body" v-loading="loading">
      <div class="editor-canvas-area">
        <div class="canvas-toolbar">
          <el-button type="primary" size="small" @click="addPlaceholder">
            <el-icon><Plus /></el-icon>
            添加占位符
          </el-button>
          <el-button size="small" @click="zoomIn">
            <el-icon><ZoomIn /></el-icon>
          </el-button>
          <el-button size="small" @click="zoomOut">
            <el-icon><ZoomOut /></el-icon>
          </el-button>
          <span class="zoom-label">{{ Math.round(scale * 100) }}%</span>
          <el-button size="small" @click="resetZoom">适应</el-button>
        </div>
        <div class="canvas-wrapper" ref="canvasWrapper">
          <v-stage
            ref="stageRef"
            :config="stageConfig"
            @mousedown="handleStageMouseDown"
          >
            <v-layer>
              <!-- 模板底图 -->
              <v-image
                v-if="templateImageObj"
                :config="{
                  image: templateImageObj,
                  width: imageWidth,
                  height: imageHeight
                }"
              />
              <!-- 占位符标记 -->
              <v-group
                v-for="(ph, index) in placeholders"
                :key="ph._uid"
                :config="{
                  x: ph.posX,
                  y: ph.posY,
                  draggable: true,
                  name: 'placeholder'
                }"
                @dragend="(e) => handleDragEnd(e, index)"
                @click="(e) => selectPlaceholder(index, e)"
                @tap="(e) => selectPlaceholder(index, e)"
              >
                <!-- 占位符文字 -->
                <v-text
                  :config="{
                    text: ph.name || '占位符',
                    fontSize: ph.fontSize,
                    fontFamily: ph.fontName,
                    fill: ph.fontColor || '#000000',
                    align: (ph.alignment || 'LEFT').toLowerCase(),
                  }"
                />
                <!-- 选中边框 -->
                <v-rect
                  v-if="selectedIndex === index"
                  :config="{
                    x: -4,
                    y: -4,
                    width: getTextWidth(ph) + 8,
                    height: (ph.fontSize || 24) + 8,
                    stroke: '#409EFF',
                    strokeWidth: 2,
                    dash: [6, 3],
                    fill: 'rgba(64,158,255,0.05)'
                  }"
                />
              </v-group>
            </v-layer>
          </v-stage>
        </div>
      </div>

      <!-- 属性面板 -->
      <div class="editor-props-panel">
        <div class="panel-title">占位符列表</div>
        <div class="placeholder-list">
          <div
            v-for="(ph, index) in placeholders"
            :key="ph._uid"
            class="placeholder-item"
            :class="{ active: selectedIndex === index }"
            @click="selectPlaceholder(index)"
          >
            <span class="ph-name">{{ ph.name || '未命名' }}</span>
            <el-button type="danger" text size="small" @click.stop="removePlaceholder(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-empty v-if="placeholders.length === 0" description="暂无占位符" :image-size="60" />
        </div>

        <template v-if="selectedIndex >= 0 && selectedIndex < placeholders.length">
          <el-divider />
          <div class="panel-title">属性配置</div>
          <el-form label-width="70px" size="small" class="props-form">
            <el-form-item label="名称">
              <el-input v-model="currentPlaceholder.name" placeholder="与Excel列名对应" />
            </el-form-item>
            <el-form-item label="X坐标">
              <el-input-number v-model="currentPlaceholder.posX" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="Y坐标">
              <el-input-number v-model="currentPlaceholder.posY" :min="0" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="字体">
              <el-select v-model="currentPlaceholder.fontName" placeholder="选择字体">
                <el-option label="宋体" value="宋体" />
                <el-option label="黑体" value="黑体" />
                <el-option label="楷体" value="楷体" />
                <el-option label="微软雅黑" value="微软雅黑" />
                <el-option label="仿宋" value="仿宋" />
                <el-option label="Arial" value="Arial" />
                <el-option label="Times New Roman" value="Times New Roman" />
              </el-select>
            </el-form-item>
            <el-form-item label="字号">
              <el-input-number v-model="currentPlaceholder.fontSize" :min="8" :max="200" :step="1" controls-position="right" />
            </el-form-item>
            <el-form-item label="颜色">
              <el-color-picker v-model="currentPlaceholder.fontColor" />
            </el-form-item>
            <el-form-item label="对齐">
              <el-radio-group v-model="currentPlaceholder.alignment">
                <el-radio-button value="LEFT">左</el-radio-button>
                <el-radio-button value="CENTER">中</el-radio-button>
                <el-radio-button value="RIGHT">右</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTemplateDetail, getTemplateImageUrl, getPlaceholders, savePlaceholders } from '@/api'

const route = useRoute()
const router = useRouter()
const templateId = computed(() => Number(route.params.id))

const loading = ref(true)
const saving = ref(false)
const template = ref(null)
const placeholders = ref([])
const selectedIndex = ref(-1)
const scale = ref(1)
const stageRef = ref(null)
const canvasWrapper = ref(null)

// 图片相关
const templateImageObj = ref(null)
const imageWidth = ref(0)
const imageHeight = ref(0)

let uidCounter = 0
const genUid = () => ++uidCounter

const stageConfig = computed(() => ({
  width: imageWidth.value * scale.value,
  height: imageHeight.value * scale.value,
  scaleX: scale.value,
  scaleY: scale.value
}))

const currentPlaceholder = computed(() => {
  if (selectedIndex.value >= 0 && selectedIndex.value < placeholders.value.length) {
    return placeholders.value[selectedIndex.value]
  }
  return {}
})

const loadTemplate = async () => {
  loading.value = true
  try {
    const { data: res } = await getTemplateDetail(templateId.value)
    if (res.code === 200 && res.data) {
      template.value = res.data
      imageWidth.value = res.data.imageWidth
      imageHeight.value = res.data.imageHeight

      // 加载图片
      const img = new Image()
      img.crossOrigin = 'anonymous'
      img.onload = () => {
        templateImageObj.value = img
        nextTick(() => fitToView())
      }
      img.src = getTemplateImageUrl(templateId.value)

      // 加载占位符
      const { data: phRes } = await getPlaceholders(templateId.value)
      if (phRes.code === 200 && phRes.data) {
        placeholders.value = phRes.data.map(p => ({ ...p, _uid: genUid() }))
      }
    }
  } catch (e) {
    ElMessage.error('加载模板失败')
  } finally {
    loading.value = false
  }
}

const fitToView = () => {
  if (!canvasWrapper.value || !imageWidth.value) return
  const wrapperWidth = canvasWrapper.value.clientWidth - 40
  const wrapperHeight = canvasWrapper.value.clientHeight - 40
  const scaleX = wrapperWidth / imageWidth.value
  const scaleY = wrapperHeight / imageHeight.value
  scale.value = Math.min(scaleX, scaleY, 1)
}

const zoomIn = () => {
  scale.value = Math.min(scale.value + 0.1, 3)
}

const zoomOut = () => {
  scale.value = Math.max(scale.value - 0.1, 0.1)
}

const resetZoom = () => {
  fitToView()
}

const addPlaceholder = () => {
  const newPh = {
    _uid: genUid(),
    templateId: templateId.value,
    name: `字段${placeholders.value.length + 1}`,
    posX: imageWidth.value / 2,
    posY: imageHeight.value / 2,
    fontName: '宋体',
    fontSize: 24,
    fontColor: '#000000',
    alignment: 'LEFT'
  }
  placeholders.value.push(newPh)
  selectedIndex.value = placeholders.value.length - 1
}

const removePlaceholder = (index) => {
  placeholders.value.splice(index, 1)
  if (selectedIndex.value >= placeholders.value.length) {
    selectedIndex.value = placeholders.value.length - 1
  }
}

const selectPlaceholder = (index, e) => {
  if (e) e.cancelBubble = true
  selectedIndex.value = index
}

const handleStageMouseDown = (e) => {
  // 点击空白区域取消选中
  const target = e.target
  const stage = stageRef.value?.getStage()
  if (target === stage) {
    selectedIndex.value = -1
  }
}

const handleDragEnd = (e, index) => {
  const node = e.target
  placeholders.value[index].posX = Math.round(node.x())
  placeholders.value[index].posY = Math.round(node.y())
  selectedIndex.value = index
}

const getTextWidth = (ph) => {
  const text = ph.name || '占位符'
  const fontSize = ph.fontSize || 24
  // 粗略估算文字宽度
  return text.length * fontSize * 0.7
}

const handleSave = async () => {
  saving.value = true
  try {
    // 去掉前端临时字段
    const data = placeholders.value.map(({ _uid, ...rest }) => rest)
    const { data: res } = await savePlaceholders(templateId.value, data)
    if (res.code === 200) {
      ElMessage.success('保存成功')
    } else {
      ElMessage.error(res.msg || '保存失败')
    }
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

const goBack = () => {
  router.push('/template')
}

// 键盘事件
const handleKeyDown = (e) => {
  if (selectedIndex.value < 0) return
  const ph = placeholders.value[selectedIndex.value]
  if (!ph) return

  const step = e.shiftKey ? 10 : 1
  switch (e.key) {
    case 'ArrowUp':
      e.preventDefault()
      ph.posY = Math.max(0, ph.posY - step)
      break
    case 'ArrowDown':
      e.preventDefault()
      ph.posY += step
      break
    case 'ArrowLeft':
      e.preventDefault()
      ph.posX = Math.max(0, ph.posX - step)
      break
    case 'ArrowRight':
      e.preventDefault()
      ph.posX += step
      break
    case 'Delete':
      removePlaceholder(selectedIndex.value)
      break
  }
}

onMounted(() => {
  loadTemplate()
  window.addEventListener('keydown', handleKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeyDown)
})
</script>

<style scoped>
.template-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e6e6e6;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.template-name {
  font-size: 16px;
  font-weight: 500;
  color: #303133;
}

.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.editor-canvas-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: #fafafa;
  border-bottom: 1px solid #eee;
}

.zoom-label {
  font-size: 13px;
  color: #606266;
  min-width: 40px;
  text-align: center;
}

.canvas-wrapper {
  flex: 1;
  overflow: auto;
  padding: 20px;
  background: #e8e8e8;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.editor-props-panel {
  width: 300px;
  border-left: 1px solid #e6e6e6;
  overflow-y: auto;
  padding: 16px;
  background: #fff;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.placeholder-list {
  max-height: 200px;
  overflow-y: auto;
}

.placeholder-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.2s;
}

.placeholder-item:hover {
  background: #f5f7fa;
}

.placeholder-item.active {
  background: #ecf5ff;
  color: #409EFF;
}

.ph-name {
  font-size: 13px;
}

.props-form {
  margin-top: 8px;
}

.props-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.props-form :deep(.el-input-number) {
  width: 100%;
}

.props-form :deep(.el-select) {
  width: 100%;
}
</style>
