const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const net = require('net')

let mainWindow = null
let backendProcess = null
const BACKEND_PORT = 18080

/**
 * 等待后端启动
 */
function waitForBackend(port, maxRetries = 30) {
  return new Promise((resolve, reject) => {
    let retries = 0
    const check = () => {
      const client = net.createConnection({ port }, () => {
        client.end()
        resolve()
      })
      client.on('error', () => {
        retries++
        if (retries >= maxRetries) {
          reject(new Error('后端启动超时'))
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
 * 优先使用内嵌 JRE，找不到再尝试系统 Java
 */
function findJavaPath() {
  // 1. 打包模式: 内嵌 JRE（在 extraResources 中）
  const bundledJre = path.join(process.resourcesPath, 'jre', 'bin', 'java.exe')
  if (fs.existsSync(bundledJre)) {
    console.log('使用内嵌 JRE:', bundledJre)
    return bundledJre
  }

  // 2. 开发模式: 项目目录下的 jre
  const devJre = path.join(__dirname, '..', '..', 'jre', 'bin', 'java.exe')
  if (fs.existsSync(devJre)) {
    console.log('使用开发目录 JRE:', devJre)
    return devJre
  }

  // 3. 兜底: 使用系统 PATH 中的 java
  console.log('使用系统 Java')
  return 'java'
}

/**
 * 启动 SpringBoot 后端
 */
function startBackend() {
  // 查找 JAR 文件
  let jarPath

  // 开发模式: 项目目录下
  const devJar = path.join(__dirname, '..', '..', 'backend', 'target', 'cert-batch-backend-1.0.0.jar')
  // 打包模式: resources 目录下
  const prodJar = path.join(process.resourcesPath, 'backend', 'cert-batch-backend.jar')

  if (fs.existsSync(devJar)) {
    jarPath = devJar
  } else if (fs.existsSync(prodJar)) {
    jarPath = prodJar
  } else {
    const errMsg = '找不到后端 JAR 文件'
    console.error(errMsg)
    console.error('开发路径:', devJar)
    console.error('生产路径:', prodJar)
    dialog.showErrorBox('启动失败', errMsg)
    return
  }

  console.log('启动后端:', jarPath)

  // 查找 Java
  const javaPath = findJavaPath()

  // 数据目录
  const dataDir = path.join(app.getPath('userData'), 'cert-batch-data')
  if (!fs.existsSync(dataDir)) {
    fs.mkdirSync(dataDir, { recursive: true })
  }

  backendProcess = spawn(javaPath, [
    '-jar', jarPath,
    `--app.data-dir=${dataDir}`,
    `--server.port=${BACKEND_PORT}`
  ], {
    cwd: path.dirname(jarPath),
    env: { ...process.env }
  })

  backendProcess.stdout.on('data', (data) => {
    console.log('[Backend]', data.toString())
  })

  backendProcess.stderr.on('data', (data) => {
    console.error('[Backend Error]', data.toString())
  })

  backendProcess.on('error', (err) => {
    console.error('[Backend] 启动失败:', err.message)
    dialog.showErrorBox('后端启动失败',
      '无法启动 Java 后端服务。\n\n' +
      '请确认内嵌 JRE 完整或系统已安装 JDK 17+。\n\n' +
      '错误信息: ' + err.message)
  })

  backendProcess.on('close', (code) => {
    console.log(`[Backend] 进程退出，代码: ${code}`)
    backendProcess = null
  })
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

/**
 * IPC: 选择目录
 */
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
app.whenReady().then(async () => {
  // 启动后端
  startBackend()

  // 等待后端启动
  try {
    await waitForBackend(BACKEND_PORT)
    console.log('后端已启动')
  } catch (e) {
    console.error('等待后端启动失败:', e.message)
  }

  createWindow()
})

// 所有窗口关闭时退出
app.on('window-all-closed', () => {
  // 关闭后端进程
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
