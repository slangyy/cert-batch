import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/template'
  },
  {
    path: '/template',
    name: 'TemplateManage',
    component: () => import('@/views/TemplateManage.vue')
  },
  {
    path: '/template/editor/:id',
    name: 'TemplateEditor',
    component: () => import('@/views/TemplateEditor.vue')
  },
  {
    path: '/generate',
    name: 'BatchGenerate',
    component: () => import('@/views/BatchGenerate.vue')
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

export default router
