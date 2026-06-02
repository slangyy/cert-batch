const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  selectDirectory: () => ipcRenderer.invoke('select-directory'),
  getMachineId: () => ipcRenderer.invoke('get-machine-id'),
  saveLicense: (data) => ipcRenderer.invoke('save-license', data),
  readLicense: () => ipcRenderer.invoke('read-license'),
  onLicenseActivated: () => ipcRenderer.invoke('on-license-activated')
})
