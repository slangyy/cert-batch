<template>
  <!-- 加载中：等待后端服务就绪 -->
  <div v-if="backendLoading" class="loading-screen">
    <div class="loading-content">
      <el-icon :size="48" color="#409EFF" class="loading-spin"><Loading /></el-icon>
      <h2>证书批量生成工具</h2>
      <p>正在启动服务，请稍候...</p>
    </div>
  </div>

  <!-- 主界面 -->
  <el-container class="app-container" v-else-if="licensed">
    <el-header class="app-header">
      <div class="header-left">
        <el-icon :size="28" color="#409EFF"><Stamp /></el-icon>
        <span class="app-title">证书批量生成工具</span>
      </div>
      <div class="header-right">
        <el-button text size="small" @click="openLogDir">
          <el-icon><FolderOpened /></el-icon>
          日志目录
        </el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="200px" class="app-aside">
        <el-menu
          :default-active="currentRoute"
          router
          class="aside-menu"
        >
          <el-menu-item index="/template">
            <el-icon><Document /></el-icon>
            <span>模板管理</span>
          </el-menu-item>
          <el-menu-item index="/generate">
            <el-icon><Printer /></el-icon>
            <span>批量生成</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <!-- 激活页 -->
  <router-view v-else />
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { validateLicenseOnline, waitForBackend } from '@/api'

const route = useRoute()
const router = useRouter()
const currentRoute = computed(() => route.path)
const licensed = ref(false)
const backendLoading = ref(true)

const openLogDir = () => {
  if (window.electronAPI?.openLogDir) {
    window.electronAPI.openLogDir()
  }
}

onMounted(async () => {
  // 1. 本地验签
  if (window.electronAPI?.verifyLicenseLocal) {
    const result = await window.electronAPI.verifyLicenseLocal()
    if (!result.valid) {
      licensed.value = false
      backendLoading.value = false
      router.replace('/activate')
      return
    }
  } else {
    // 非 Electron 环境（浏览器开发），直接放行
    licensed.value = true
    backendLoading.value = false
    router.replace('/template')
    return
  }

  // 2. 等待本地后端就绪（轮询 API 直到可访问）
  try {
    await waitForBackend()
  } catch (e) {
    // 超时也放行，让页面自行处理请求失败
  }

  // 3. 后端就绪，显示主界面
  licensed.value = true
  backendLoading.value = false
  router.replace('/template')

  // 4. 后台在线验证（不阻塞用户）
  try {
    const licenseInfo = await window.electronAPI.readLicense()
    if (licenseInfo?.token) {
      const { data: res } = await validateLicenseOnline(licenseInfo.token, licenseInfo.machineId)
      if (res.code !== 200) {
        await window.electronAPI.saveLicense(null)
        licensed.value = false
        router.replace('/activate')
      }
    }
  } catch (e) {
    // 网络不通，离线容忍
  }
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  width: 100%;
}

.loading-screen {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}

.loading-content {
  text-align: center;
}

.loading-content h2 {
  margin: 16px 0 8px;
  font-size: 22px;
  color: #303133;
}

.loading-content p {
  color: #909399;
  font-size: 14px;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.loading-spin {
  animation: spin 1.5s linear infinite;
}

.app-container {
  height: 100%;
}

.app-header {
  background: #fff;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 56px !important;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-right {
  margin-left: auto;
}

.app-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.app-aside {
  background: #fff;
  border-right: 1px solid #e6e6e6;
}

.aside-menu {
  border-right: none;
  height: 100%;
}

.app-main {
  background: #f5f7fa;
  padding: 20px;
}
</style>
