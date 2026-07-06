<template>
  <router-view v-if="$route.path === '/login'" />
  <el-container v-else class="app-shell">
    <el-aside width="188px" class="side-nav">
      <div class="brand">text2sql</div>
      <el-menu router :default-active="$route.path" class="nav-menu">
        <el-menu-item index="/datasources"><el-icon><Coin /></el-icon><span>数据源</span></el-menu-item>
        <el-menu-item index="/metadata"><el-icon><Grid /></el-icon><span>元数据</span></el-menu-item>
        <el-menu-item index="/query"><el-icon><MagicStick /></el-icon><span>查询工作台</span></el-menu-item>
        <el-menu-item index="/history"><el-icon><Clock /></el-icon><span>查询历史</span></el-menu-item>
        <el-menu-item v-if="isAdmin" index="/models"><el-icon><Setting /></el-icon><span>模型配置</span></el-menu-item>
        <el-menu-item v-if="isAdmin" index="/analytics"><el-icon><TrendCharts /></el-icon><span>模型分析</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar">
        <div class="page-title">{{ $route.meta.title || 'text2sql' }}</div>
        <div class="top-actions">
          <span>{{ user?.displayName || user?.username }}</span>
          <el-tag size="small" effect="plain">{{ user?.role }}</el-tag>
          <el-button link @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from './api/client'

const user = ref<any>(JSON.parse(localStorage.getItem('user') || 'null'))
const router = useRouter()
const isAdmin = computed(() => user.value?.role === 'ADMIN')

onMounted(async () => {
  if (!user.value && localStorage.getItem('token')) {
    user.value = await api.get('/me')
    localStorage.setItem('user', JSON.stringify(user.value))
  }
})

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
}
</script>
