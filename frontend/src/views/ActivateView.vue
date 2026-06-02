<template>
  <div class="activate-page">
    <div class="activate-card">
      <div class="card-header">
        <el-icon :size="40" color="#409EFF"><Stamp /></el-icon>
        <h2>证书批量生成工具</h2>
        <p class="subtitle">请输入授权码激活软件（激活需联网）</p>
      </div>

      <div class="card-body">
        <el-form @submit.prevent="handleActivate">
          <el-form-item>
            <el-input
              v-model="licenseKey"
              placeholder="请输入授权码，如：XXXX-XXXX-XXXX-XXXX"
              size="large"
              clearable
              :disabled="activating"
              style="text-align: center; font-size: 18px; letter-spacing: 2px;"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            style="width: 100%; margin-top: 12px;"
            :loading="activating"
            @click="handleActivate"
          >
            {{ activating ? '激活中...' : '激活' }}
          </el-button>
        </el-form>

        <div v-if="errorMsg" class="error-msg">
          <el-icon><WarningFilled /></el-icon>
          {{ errorMsg }}
        </div>

        <div class="tips">
          <p>如需获取授权码，请联系客服购买</p>
          <p>本机机器码：<span class="machine-id">{{ machineId || '获取中...' }}</span></p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { activateLicense } from '@/api'

const licenseKey = ref('')
const activating = ref(false)
const errorMsg = ref('')
const machineId = ref('')

onMounted(async () => {
  if (window.electronAPI?.getMachineId) {
    machineId.value = await window.electronAPI.getMachineId()
  }
})

const handleActivate = async () => {
  const key = licenseKey.value.trim()
  if (!key) {
    errorMsg.value = '请输入授权码'
    return
  }
  if (!machineId.value) {
    errorMsg.value = '无法获取机器标识，请重启应用'
    return
  }

  activating.value = true
  errorMsg.value = ''

  try {
    const { data: res } = await activateLicense(key, machineId.value)
    if (res.code === 200 && res.data) {
      // 保存完整授权信息（含签名），供离线验证使用
      const licenseData = {
        machineId: machineId.value,
        licenseKey: key,
        token: res.data.token,
        expireAt: res.data.expireAt,
        issuedAt: res.data.activatedAt,
        signature: res.data.licenseSignature
      }
      if (window.electronAPI?.saveLicense) {
        await window.electronAPI.saveLicense(licenseData)
      }
      ElMessage.success('激活成功！')
      window.electronAPI?.onLicenseActivated()
    } else {
      errorMsg.value = res.msg || '激活失败'
    }
  } catch (e) {
    if (e.code === 'ERR_NETWORK' || e.message?.includes('Network Error')) {
      errorMsg.value = '网络连接失败，激活需联网，请检查网络后重试'
    } else {
      errorMsg.value = '激活失败: ' + (e.message || '未知错误')
    }
  } finally {
    activating.value = false
  }
}
</script>

<style scoped>
.activate-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.activate-card {
  width: 420px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px 32px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.2);
}

.card-header {
  text-align: center;
  margin-bottom: 32px;
}

.card-header h2 {
  margin: 12px 0 8px;
  font-size: 22px;
  color: #303133;
}

.subtitle {
  color: #909399;
  font-size: 14px;
}

.error-msg {
  color: #F56C6C;
  font-size: 13px;
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.tips {
  margin-top: 24px;
  text-align: center;
  color: #909399;
  font-size: 12px;
}

.tips p {
  margin: 4px 0;
}

.machine-id {
  font-family: monospace;
  color: #606266;
  user-select: all;
  cursor: pointer;
  word-break: break-all;
}
</style>
