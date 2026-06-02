import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/activate',
    name: 'Activate',
    component: () => import('@/views/ActivateView.vue'),
    meta: { public: true }
  },
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

// 路由守卫：未授权时跳转到激活页
router.beforeEach((to, from, next) => {
  if (to.meta.public) {
    next()
    return
  }
  const licenseStr = localStorage.getItem('license_info')
  if (!licenseStr) {
    next('/activate')
  } else {
    next()
  }
})

export default router
