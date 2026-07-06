import { createRouter, createWebHistory } from 'vue-router'
import Login from './views/Login.vue'
import QueryWorkbench from './views/QueryWorkbench.vue'
import DataSources from './views/DataSources.vue'
import Metadata from './views/Metadata.vue'
import Models from './views/Models.vue'
import History from './views/History.vue'
import Analytics from './views/Analytics.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: Login },
    { path: '/', redirect: '/query' },
    { path: '/query', component: QueryWorkbench, meta: { title: '查询工作台' } },
    { path: '/datasources', component: DataSources, meta: { title: '数据源' } },
    { path: '/metadata', component: Metadata, meta: { title: '元数据' } },
    { path: '/models', component: Models, meta: { title: '模型配置' } },
    { path: '/history', component: History, meta: { title: '查询历史' } },
    { path: '/analytics', component: Analytics, meta: { title: '模型分析' } }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('token')) {
    return '/login'
  }
})

export default router
