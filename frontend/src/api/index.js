import axios from 'axios'

// 统一使用远程后端服务器地址
const REMOTE_BASE = 'http://8.152.161.203:18080'
const BASE_URL = `${REMOTE_BASE}/api`

const api = axios.create({
  baseURL: BASE_URL,
  timeout: 60000
})

// 请求拦截：自动携带授权 token 和机器码
api.interceptors.request.use(config => {
  // 从 localStorage 读取授权信息（渲染进程可访问）
  const licenseStr = localStorage.getItem('license_info')
  if (licenseStr) {
    try {
      const license = JSON.parse(licenseStr)
      if (license.token) config.headers['X-License-Token'] = license.token
      if (license.machineId) config.headers['X-Machine-Id'] = license.machineId
    } catch (e) { /* ignore */ }
  }
  return config
})

// 响应拦截：401 时跳转激活页
api.interceptors.response.use(
  res => res,
  err => {
    if (err.response && err.response.status === 401) {
      // 授权失效，清除本地授权并跳转激活页
      localStorage.removeItem('license_info')
      window.location.hash = '#/activate'
    }
    return Promise.reject(err)
  }
)

// ===== 授权相关 =====

/** 激活授权码 */
export function activateLicense(licenseKey, machineId) {
  return api.post('/license/activate', { licenseKey, machineId })
}

/** 验证授权 */
export function validateLicense(token, machineId) {
  return api.post('/license/validate', { token, machineId })
}

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
  const token = (() => {
    try {
      const l = JSON.parse(localStorage.getItem('license_info') || '{}')
      return l.token || ''
    } catch { return '' }
  })()
  const machineId = (() => {
    try {
      const l = JSON.parse(localStorage.getItem('license_info') || '{}')
      return l.machineId || ''
    } catch { return '' }
  })()
  return `${REMOTE_BASE}/api/template/${id}/image?token=${encodeURIComponent(token)}&machineId=${encodeURIComponent(machineId)}`
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
