const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const net = require('net')
const crypto = require('crypto')
const { execSync } = require('child_process')

let mainWindow = null
let backendProcess = null
const BACKEND_PORT = 18080

// ===== RSA 公钥（从服务器 license/public-key 获取后替换到这里） =====
// 首次部署后端后，访问 GET /api/license/public-key 获取公钥，粘贴到下面
const LICENSE_PUBLIC_KEY = `PLACEHOLDER_REPLACE_WITH_YOUR_PUBLIC_KEY`

/**
 * 获取本机唯一标识（基于主板 UUID + 硬盘序列号）
 */
function getMachineId() {
  try {
    let raw = ''
    try {
      const uuid = execSync('wmic csproduct get UUID', { encoding: 'utf-8' })
        .split('\n').filter(l => l.trim() && !l.includes('UUID'))[0]?.trim() || ''
      raw += uuid
    } catch (e) { /* ignore */ }
    try {
      const sn = execSync('wmic diskdrive get SerialNumber', { encoding: 'utf-8' })
        .split('\n').filter(l => l.trim() && !l.includes('SerialNumber'))[0]?.trim() || ''
      raw += sn
    } catch (e) { /* ignore */ }

    if (raw) {
      return crypto.createHash('sha256').update(raw).digest('hex')
    }
  } catch (e) { /* ignore */ }

  // 兜底
  const fallbackPath = path.join(app.getPath('userData'), '.machine_id')
  if (fs.existsSync(fallbackPath)) {
    return fs.readFileSync(fallbackPath, 'utf-8').trim()
  }
  const fallbackId = crypto.randomBytes(32).toString('hex')
  fs.writeFileSync(fallbackPath, fallbackId)
  return fallbackId
}

/**
 * 等待后端启动
 */
function waitForBackend(port, maxRetries = 60) {
  return new Promise((resolve, reject) => {
    let retries = 0
    let rejected = false

    const onClose = (code) => {
      if (!rejected) {
        rejected = true
        reject(new Error(`后端进程意外退出（退出码: ${code}）`))
      }
    }
    if (backendProcess) {
      backendProcess.once('close', onClose)
    }

    const check = () => {
      if (rejected) return
      const client = net.createConnection({ port }, () => {
        client.end()
        rejected = true
        if (backendProcess) backendProcess.removeListener('close', onClose)
        resolve()
      })
      client.on('error', () => {
        retries++
        if (retries >= maxRetries) {
          rejected = true
          if (backendProcess) backendProcess.removeListener('close', onClose)
          reject(new Error(`后端启动超时（已等待 ${maxRetries} 秒）`))
        } else {
          setTimeout(check, 1000)
        }
      })
    }
    check()
  })
}

/**
 * 查找 Java 可执行文件路径
 */
function findJavaPath() {
  const bundledJre = path.join(process.resourcesPath, 'jre', 'bin', 'java.exe')
  if (fs.existsSync(bundledJre)) return bundledJre

  const devJre = path.join(__dirname, '..', '..', 'jre', 'bin', 'java.exe')
  if (fs.existsSync(devJre)) return devJre

  return 'java'
}

// 用于收集后端启动日志
let backendErrorLog = []

/**
 * 获取后端日志文件路径
 */
function getBackendLogPath() {
  return path.join(app.getPath('userData'), 'backend.log')
}

/**
 * 启动 SpringBoot 后端
 */
function startBackend() {
  let jarPath
  const devJar = path.join(__dirname, '..', '..', 'backend', 'target', 'cert-batch-backend-1.0.0.jar')
  const prodJar = path.join(process.resourcesPath, 'backend', 'cert-batch-backend.jar')

  if (fs.existsSync(devJar)) {
    jarPath = devJar
  } else if (fs.existsSync(prodJar)) {
    jarPath = prodJar
  } else {
    console.error('找不到后端 JAR 文件')
    console.error('开发路径:', devJar)
    console.error('生产路径:', prodJar)
    dialog.showErrorBox('启动失败',
      '找不到后端 JAR 文件。\n\n' +
      '开发路径: ' + devJar + '\n' +
      '生产路径: ' + prodJar)
    return
  }

  const javaPath = findJavaPath()
  console.log('Java路径:', javaPath)
  console.log('JAR路径:', jarPath)

  const dataDir = path.join(app.getPath('userData'), 'cert-batch-data')
  if (!fs.existsSync(dataDir)) {
    fs.mkdirSync(dataDir, { recursive: true })
  }

  // 先测试 Java 是否可用
  try {
    const testResult = require('child_process').execSync(`"${javaPath}" -version 2>&1`, { encoding: 'utf-8', timeout: 5000 })
    console.log('[Java Version]', testResult)
  } catch (e) {
    const versionOutput = e.stdout || e.stderr || e.message || ''
    dialog.showErrorBox('Java 环境异常',
      '无法运行 Java，请检查 JRE 是否完整。\n\n' +
      'Java路径: ' + javaPath + '\n\n' +
      '输出: ' + versionOutput.substring(0, 500))
    return
  }

  backendErrorLog = []
  const logPath = getBackendLogPath()
  // 清空旧日志
  fs.writeFileSync(logPath, `=== ${new Date().toISOString()} Backend Starting ===\n` +
    `Java: ${javaPath}\nJAR: ${jarPath}\n\n`, 'utf-8')

  const logStream = fs.createWriteStream(logPath, { flags: 'a' })

  backendProcess = spawn(javaPath, [
    '-jar', jarPath,
    `--app.data-dir=${dataDir}`,
    `--server.port=${BACKEND_PORT}`,
    '--app.license-check=false'
  ], {
    cwd: path.dirname(jarPath),
    env: { ...process.env },
    stdio: ['pipe', 'pipe', 'pipe']
  })

  backendProcess.stdout.on('data', (data) => {
    const msg = data.toString()
    console.log('[Backend]', msg)
    backendErrorLog.push(msg)
    if (backendErrorLog.length > 100) backendErrorLog.shift()
    logStream.write(msg)
  })

  backendProcess.stderr.on('data', (data) => {
    const msg = data.toString()
    console.error('[Backend Error]', msg)
    backendErrorLog.push(msg)
    if (backendErrorLog.length > 100) backendErrorLog.shift()
    logStream.write(msg)
  })

  backendProcess.on('error', (err) => {
    logStream.end()
    console.error('[Backend] 启动失败:', err.message)
    dialog.showErrorBox('后端启动失败',
      '无法启动 Java 后端服务。\n\n' +
      'Java路径: ' + javaPath + '\n' +
      '错误: ' + err.message)
  })

  backendProcess.on('close', async (code) => {
    logStream.end()
    console.log(`[Backend] 进程退出，代码: ${code}`)
    if (code !== 0 && code !== null) {
      let logContent = ''
      try {
        logContent = fs.readFileSync(logPath, 'utf-8')
      } catch (e) { /* ignore */ }

      const tail = logContent.length > 2000 ? logContent.slice(-2000) : logContent

      const { shell } = require('electron')
      const result = await dialog.showMessageBox(mainWindow, {
        type: 'error',
        title: '后端启动失败',
        message: '后端进程意外退出（退出码: ' + code + '）',
        detail:
          'Java路径: ' + javaPath + '\n' +
          'JAR路径: ' + jarPath + '\n\n' +
          '日志文件: ' + logPath + '\n\n' +
          '最近日志:\n' + (tail || '（无输出）'),
        buttons: ['打开日志目录', '确定'],
        defaultId: 1
      })

      if (result.response === 0) {
        shell.openPath(path.dirname(logPath))
      }
    }
    backendProcess = null
  })
}

/**
 * 本地验证授权签名（离线可用）
 * 返回 { valid: boolean, reason?: string }
 */
function verifyLicenseLocal() {
  const licensePath = path.join(app.getPath('userData'), 'license.json')
  if (!fs.existsSync(licensePath)) {
    return { valid: false, reason: '未找到授权文件' }
  }

  try {
    const license = JSON.parse(fs.readFileSync(licensePath, 'utf-8'))
    const { machineId, licenseKey, expireAt, issuedAt, signature } = license

    if (!machineId || !licenseKey || !signature) {
      return { valid: false, reason: '授权文件不完整' }
    }

    // 1. 检查机器码
    const currentMachineId = getMachineId()
    if (machineId !== currentMachineId) {
      return { valid: false, reason: '授权与当前设备不匹配' }
    }

    // 2. 检查过期
    if (expireAt && expireAt !== 'permanent') {
      const expireDate = new Date(expireAt)
      if (expireDate < new Date()) {
        return { valid: false, reason: '授权已过期' }
      }
    }

    // 3. 验证 RSA 签名
    if (LICENSE_PUBLIC_KEY === 'PLACEHOLDER_REPLACE_WITH_YOUR_PUBLIC_KEY') {
      // 公钥未配置时，跳过签名验证（开发阶段）
      console.warn('[License] 公钥未配置，跳过签名验证')
      return { valid: true }
    }

    const expire = expireAt || 'permanent'
    const issued = issuedAt || ''
    const signedData = `${machineId}|${licenseKey}|${expire}|${issued}`

    const verifier = crypto.createVerify('SHA256')
    verifier.update(signedData)
    verifier.end()

    const publicKeyObj = crypto.createPublicKey({
      key: Buffer.from(LICENSE_PUBLIC_KEY, 'base64'),
      type: 'spki',
      format: 'der'
    })

    const valid = verifier.verify(publicKeyObj, Buffer.from(signature, 'base64'))
    if (!valid) {
      return { valid: false, reason: '授权签名无效，文件可能被篡改' }
    }

    return { valid: true }
  } catch (e) {
    console.error('[License] 验证失败:', e.message)
    return { valid: false, reason: '授权验证异常: ' + e.message }
  }
}

/**
 * 创建主窗口
 */
function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1000,
    minHeight: 700,
    title: '证书批量生成工具',
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    }
  })

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
    mainWindow.webContents.openDevTools()
  } else {
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

// ===== IPC 处理 =====

ipcMain.handle('get-machine-id', () => getMachineId())

ipcMain.handle('save-license', (event, licenseData) => {
  const filePath = path.join(app.getPath('userData'), 'license.json')
  fs.writeFileSync(filePath, JSON.stringify(licenseData, null, 2), 'utf-8')
  return true
})

ipcMain.handle('read-license', () => {
  const filePath = path.join(app.getPath('userData'), 'license.json')
  if (fs.existsSync(filePath)) {
    try {
      return JSON.parse(fs.readFileSync(filePath, 'utf-8'))
    } catch (e) {
      return null
    }
  }
  return null
})

ipcMain.handle('verify-license-local', () => verifyLicenseLocal())

ipcMain.handle('on-license-activated', async () => {
  // 激活后需要启动本地后端，等就绪后刷新页面
  if (backendProcess) {
    // 后端已在运行，直接刷新
    if (mainWindow) mainWindow.reload()
    return
  }

  startBackend()

  try {
    await waitForBackend(BACKEND_PORT)
    console.log('激活后后端已启动')
    if (mainWindow) mainWindow.reload()
  } catch (e) {
    console.error('激活后启动后端失败:', e.message)
    dialog.showErrorBox('启动失败', '后端服务启动失败。\n详细信息: ' + e.message)
  }
})

ipcMain.handle('select-directory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory']
  })
  if (result.canceled) return null
  return result.filePaths[0]
})

ipcMain.handle('open-log-dir', () => {
  const { shell } = require('electron')
  const logDir = path.dirname(getBackendLogPath())
  shell.openPath(logDir)
})

ipcMain.handle('open-path', (event, targetPath) => {
  const { shell } = require('electron')
  shell.openPath(targetPath)
})

// 应用启动
app.whenReady().then(async () => {
  // 先检查本地授权，再决定启动流程
  const licenseResult = verifyLicenseLocal()

  if (!licenseResult.valid) {
    // 无授权 → 创建窗口，Vue 会自动显示激活页
    console.log('本地授权无效:', licenseResult.reason)
    createWindow()
    return
  }

  // 有授权 → 先启动后端，再创建窗口
  createWindow()

  startBackend()

  try {
    await waitForBackend(BACKEND_PORT)
    console.log('后端已启动')
    if (mainWindow) mainWindow.reload()
  } catch (e) {
    console.error('等待后端启动失败:', e.message)
    dialog.showErrorBox('启动失败', '后端服务启动失败。\n详细信息: ' + e.message)
  }
})

app.on('window-all-closed', () => {
  if (backendProcess) {
    backendProcess.kill()
    backendProcess = null
  }
  app.quit()
})

app.on('before-quit', () => {
  if (backendProcess) {
    backendProcess.kill()
    backendProcess = null
  }
})
