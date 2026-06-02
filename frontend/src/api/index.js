import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 60000
})

// ===== 模板相关 =====

/** 获取模板列表 */
export function getTemplateList() {
  return api.get('/template/list')
}

/** 获取模板详情 */
export function getTemplateDetail(id) {
  return api.get(`/template/${id}`)
}

/** 创建模板 */
export function createTemplate(name, imageFile) {
  const formData = new FormData()
  formData.append('name', name)
  formData.append('image', imageFile)
  return api.post('/template/create', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 更新模板名称 */
export function updateTemplateName(id, name) {
  return api.put(`/template/${id}/name`, null, { params: { name } })
}

/** 删除模板 */
export function deleteTemplate(id) {
  return api.delete(`/template/${id}`)
}

/** 获取模板图片URL */
export function getTemplateImageUrl(id) {
  return `/api/template/${id}/image`
}

/** 获取模板占位符 */
export function getPlaceholders(templateId) {
  return api.get(`/template/${templateId}/placeholders`)
}

/** 保存模板占位符 */
export function savePlaceholders(templateId, placeholders) {
  return api.post(`/template/${templateId}/placeholders`, placeholders)
}

// ===== 证书生成相关 =====

/** 解析Excel文件 */
export function parseExcel(file) {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/certificate/parse-excel', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 批量生成证书 */
export function batchGenerate(params) {
  return api.post('/certificate/batch-generate', params, {
    timeout: 300000 // 5分钟超时
  })
}

/** 预览证书 */
export function previewCertificate(templateId, data) {
  return api.post('/certificate/preview', { templateId, data }, {
    responseType: 'blob'
  })
}

export default api
