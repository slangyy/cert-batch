const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  selectDirectory: () => ipcRenderer.invoke('select-directory'),
  getMachineId: () => ipcRenderer.invoke('get-machine-id'),
  saveLicense: (data) => ipcRenderer.invoke('save-license', data),
  readLicense: () => ipcRenderer.invoke('read-license'),
  verifyLicenseLocal: () => ipcRenderer.invoke('verify-license-local'),
  onLicenseActivated: () => ipcRenderer.invoke('on-license-activated'),
  openLogDir: () => ipcRenderer.invoke('open-log-dir'),
  openPath: (targetPath) => ipcRenderer.invoke('open-path', targetPath)
})
