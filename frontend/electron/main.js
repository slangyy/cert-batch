const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const path = require('path')
const fs = require('fs')
const { execSync } = require('child_process')
const crypto = require('crypto')

let mainWindow = null

/**
 * 获取本机唯一标识（基于主板 UUID + 硬盘序列号）
 */
function getMachineId() {
  try {
    let raw = ''
    // 主板 UUID
    try {
      const uuid = execSync('wmic csproduct get UUID', { encoding: 'utf-8' })
        .split('\n').filter(l => l.trim() && !l.includes('UUID'))[0]?.trim() || ''
      raw += uuid
    } catch (e) { /* ignore */ }
    // 硬盘序列号
    try {
      const sn = execSync('wmic diskdrive get SerialNumber', { encoding: 'utf-8' })
        .split('\n').filter(l => l.trim() && !l.includes('SerialNumber'))[0]?.trim() || ''
      raw += sn
    } catch (e) { /* ignore */ }

    if (raw) {
      return crypto.createHash('sha256').update(raw).digest('hex')
    }
  } catch (e) { /* ignore */ }

  // 兜底：使用随机 ID（仅限无法获取硬件信息时）
  const fallbackPath = path.join(app.getPath('userData'), '.machine_id')
  if (fs.existsSync(fallbackPath)) {
    return fs.readFileSync(fallbackPath, 'utf-8').trim()
  }
  const fallbackId = crypto.randomBytes(32).toString('hex')
  fs.writeFileSync(fallbackPath, fallbackId)
  return fallbackId
}

/**
 * 获取本地授权文件路径
 */
function getLicenseFilePath() {
  return path.join(app.getPath('userData'), 'license.json')
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
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    }
  })

  // 开发模式加载 Vite 开发服务器
  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
    mainWindow.webContents.openDevTools()
  } else {
    // 生产模式加载构建后的文件
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

// ===== IPC 处理 =====

/** 获取机器码 */
ipcMain.handle('get-machine-id', () => {
  return getMachineId()
})

/** 保存授权信息到本地文件 */
ipcMain.handle('save-license', (event, licenseData) => {
  const filePath = getLicenseFilePath()
  fs.writeFileSync(filePath, JSON.stringify(licenseData), 'utf-8')
  return true
})

/** 读取本地授权信息 */
ipcMain.handle('read-license', () => {
  const filePath = getLicenseFilePath()
  if (fs.existsSync(filePath)) {
    try {
      return JSON.parse(fs.readFileSync(filePath, 'utf-8'))
    } catch (e) {
      return null
    }
  }
  return null
})

/** 授权激活后刷新页面 */
ipcMain.handle('on-license-activated', () => {
  if (mainWindow) {
    mainWindow.reload()
  }
})

/** IPC: 选择目录 */
ipcMain.handle('select-directory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory']
  })
  if (result.canceled) {
    return null
  }
  return result.filePaths[0]
})

// 应用启动
app.whenReady().then(() => {
  createWindow()
})

// 所有窗口关闭时退出
app.on('window-all-closed', () => {
  app.quit()
})
