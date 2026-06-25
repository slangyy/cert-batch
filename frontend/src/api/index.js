import axios from 'axios'

// 业务 API：本地后端
const LOCAL_BASE = 'http://localhost:18080'
const LOCAL_API = axios.create({
  baseURL: `${LOCAL_BASE}/api`,
  timeout: 60000
})

// 授权 API：远程服务器（仅激活/验证时使用）
const REMOTE_BASE = 'http://8.152.161.203:18080'
const LICENSE_API = axios.create({
  baseURL: `${REMOTE_BASE}/api`,
  timeout: 30000
})

// ===== 授权相关（走远程服务器） =====

/** 激活授权码 */
export function activateLicense(licenseKey, machineId) {
  return LICENSE_API.post('/license/activate', { licenseKey, machineId })
}

/** 在线验证授权（检查是否被禁用） */
export function validateLicenseOnline(token, machineId) {
  return LICENSE_API.post('/license/validate', { token, machineId })
}

// ===== 后端就绪检测 =====

/** 等待本地后端就绪（轮询直到 /api/template/list 返回成功） */
export function waitForBackend(maxRetries = 60, interval = 1000) {
  return new Promise((resolve, reject) => {
    let retries = 0
    const check = () => {
      LOCAL_API.get('/template/list').then(res => {
        resolve(true)
      }).catch(() => {
        retries++
        if (retries >= maxRetries) {
          reject(new Error('后端服务启动超时'))
        } else {
          setTimeout(check, interval)
        }
      })
    }
    check()
  })
}

// ===== 模板相关（走本地后端） =====

/** 获取模板列表 */
export function getTemplateList() {
  return LOCAL_API.get('/template/list')
}

/** 获取模板详情 */
export function getTemplateDetail(id) {
  return LOCAL_API.get(`/template/${id}`)
}

/** 创建模板 */
export function createTemplate(name, imageFile) {
  const formData = new FormData()
  formData.append('name', name)
  formData.append('image', imageFile)
  return LOCAL_API.post('/template/create', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 更新模板名称 */
export function updateTemplateName(id, name) {
  return LOCAL_API.put(`/template/${id}/name`, null, { params: { name } })
}

/** 删除模板 */
export function deleteTemplate(id) {
  return LOCAL_API.delete(`/template/${id}`)
}

/** 获取模板图片URL */
export function getTemplateImageUrl(id) {
  return `${LOCAL_BASE}/api/template/${id}/image`
}

/** 获取模板占位符 */
export function getPlaceholders(templateId) {
  return LOCAL_API.get(`/template/${templateId}/placeholders`)
}

/** 保存模板占位符 */
export function savePlaceholders(templateId, placeholders) {
  return LOCAL_API.post(`/template/${templateId}/placeholders`, placeholders)
}

// ===== 证书生成相关（走本地后端） =====

/** 解析Excel文件 */
export function parseExcel(file) {
  const formData = new FormData()
  formData.append('file', file)
  return LOCAL_API.post('/certificate/parse-excel', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/** 批量生成证书（SSE 流式进度） */
export function batchGenerateSSE(params, onProgress, onComplete, onError) {
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

  const fetchOptions = {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-License-Token': token,
      'X-Machine-Id': machineId
    },
    body: JSON.stringify(params)
  }

  fetch(`${LOCAL_BASE}/api/certificate/batch-generate`, fetchOptions)
    .then(response => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) return
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            if (line.startsWith('event:progress')) continue
            if (line.startsWith('event:complete')) continue
            if (line.startsWith('event:error')) continue
            if (line.startsWith('data:')) {
              const data = line.slice(5).trim()
              if (!data) continue
              try {
                const parsed = JSON.parse(data)
                // 判断事件类型需看上一个 event 行，简化处理：
                // progress 有 current/total/percent 字段
                if (parsed.current !== undefined) {
                  onProgress?.(parsed)
                } else if (parsed.total !== undefined && parsed.success !== undefined && parsed.current === undefined) {
                  onComplete?.(parsed)
                } else if (parsed.code !== undefined) {
                  onError?.(parsed.msg || '生成失败')
                }
              } catch (e) { /* ignore */ }
            }
          }
          read()
        })
      }
      read()
    })
    .catch(err => {
      onError?.(err.message || '网络错误')
    })
}

/** 批量生成证书（上传Excel文件，SSE 流式进度） */
export function batchGenerateFileSSE(params, onProgress, onComplete, onError) {
  const formData = new FormData()
  formData.append('templateId', params.templateId)
  formData.append('file', params.file)
  formData.append('outputDir', params.outputDir)
  formData.append('format', params.format)
  if (params.fileNameField) {
    formData.append('fileNameField', params.fileNameField)
  }

  fetch(`${LOCAL_BASE}/api/certificate/batch-generate-file`, {
    method: 'POST',
    body: formData
  })
    .then(response => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = ''
      let completed = false

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            if (!completed) {
              onError?.('生成连接已断开，请检查输出目录和后端日志')
            }
            return
          }
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            const trimmed = line.trim()
            if (trimmed.startsWith('event:')) {
              eventName = trimmed.slice(6).trim()
              continue
            }
            if (trimmed.startsWith('data:')) {
              const data = trimmed.slice(5).trim()
              if (!data) continue
              try {
                const parsed = JSON.parse(data)
                if (eventName === 'progress' || parsed.current !== undefined) {
                  onProgress?.(parsed)
                } else if (eventName === 'complete') {
                  completed = true
                  onComplete?.(parsed)
                } else if (eventName === 'error' || parsed.code !== undefined) {
                  completed = true
                  onError?.(parsed.msg || '生成失败')
                }
              } catch (e) { /* ignore */ }
            }
          }
          read()
        }).catch(err => {
          onError?.(err.message || '生成连接异常')
        })
      }
      read()
    })
    .catch(err => {
      onError?.(err.message || '网络错误')
    })
}

/** Promise 封装：批量生成证书（上传Excel文件，SSE 流式进度） */
export function batchGenerateFileSSEPromise(params, onProgress) {
  return new Promise((resolve, reject) => {
    batchGenerateFileSSE(
      params,
      onProgress,
      resolve,
      (message) => reject(new Error(message || '生成失败'))
    )
  })
}

/** 生成小程序上传 ZIP 包（上传数据Excel和list.xlsx模板，SSE 流式进度） */
export function batchGenerateMiniProgramZipSSE(params, onProgress, onComplete, onError) {
  const formData = new FormData()
  formData.append('templateId', params.templateId)
  formData.append('dataFile', params.dataFile)
  formData.append('listTemplateFile', params.listTemplateFile)
  formData.append('outputDir', params.outputDir)
  formData.append('guidColumn', params.guidColumn)
  formData.append('certificateFolderName', params.certificateFolderName)

  fetch(`${LOCAL_BASE}/api/certificate/mini-program-zip`, {
    method: 'POST',
    body: formData
  })
    .then(response => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = ''
      let completed = false

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            if (!completed) {
              onError?.('生成连接已断开，请检查输出目录和后端日志')
            }
            return
          }
          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            const trimmed = line.trim()
            if (trimmed.startsWith('event:')) {
              eventName = trimmed.slice(6).trim()
              continue
            }
            if (trimmed.startsWith('data:')) {
              const data = trimmed.slice(5).trim()
              if (!data) continue
              try {
                const parsed = JSON.parse(data)
                if (eventName === 'progress' || parsed.current !== undefined) {
                  onProgress?.(parsed)
                } else if (eventName === 'complete') {
                  completed = true
                  onComplete?.(parsed)
                } else if (eventName === 'error' || parsed.code !== undefined) {
                  completed = true
                  onError?.(parsed.msg || '生成失败')
                }
              } catch (e) { /* ignore */ }
            }
          }
          read()
        }).catch(err => {
          onError?.(err.message || '生成连接异常')
        })
      }
      read()
    })
    .catch(err => {
      onError?.(err.message || '网络错误')
    })
}

/** Promise 封装：生成小程序上传 ZIP 包 */
export function batchGenerateMiniProgramZipSSEPromise(params, onProgress) {
  return new Promise((resolve, reject) => {
    batchGenerateMiniProgramZipSSE(
      params,
      onProgress,
      resolve,
      (message) => reject(new Error(message || '生成失败'))
    )
  })
}

/** 预览证书 */
export function previewCertificate(templateId, data) {
  return LOCAL_API.post('/certificate/preview', { templateId, data }, {
    responseType: 'blob'
  })
}

export default LOCAL_API
