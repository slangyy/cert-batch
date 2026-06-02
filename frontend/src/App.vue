<template>
  <el-container class="app-container" v-if="licensed">
    <el-header class="app-header">
      <div class="header-left">
        <el-icon :size="28" color="#409EFF"><Stamp /></el-icon>
        <span class="app-title">证书批量生成工具</span>
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
  <router-view v-else />
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { validateLicense } from '@/api'

const route = useRoute()
const router = useRouter()
const currentRoute = computed(() => route.path)
const licensed = ref(false)

onMounted(async () => {
  // 从 Electron 本地文件读取授权信息
  let licenseInfo = null
  if (window.electronAPI?.readLicense) {
    licenseInfo = await window.electronAPI.readLicense()
  }

  if (!licenseInfo || !licenseInfo.token) {
    // 无本地授权，跳转激活页
    licensed.value = false
    router.replace('/activate')
    return
  }

  // 同步到 localStorage（供 axios 拦截器使用）
  localStorage.setItem('license_info', JSON.stringify(licenseInfo))

  // 向服务端验证授权有效性
  try {
    const { data: res } = await validateLicense(licenseInfo.token, licenseInfo.machineId)
    if (res.code === 200) {
      licensed.value = true
      router.replace('/template')
    } else {
      // 授权无效
      localStorage.removeItem('license_info')
      licensed.value = false
      router.replace('/activate')
    }
  } catch (e) {
    // 网络不通时，如果有本地授权则允许使用（离线容忍）
    licensed.value = true
    router.replace('/template')
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
