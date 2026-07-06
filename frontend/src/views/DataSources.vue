<template>
  <div class="stack">
    <div class="panel">
      <div class="panel-header">
        <span>数据源管理</span>
        <el-button type="primary" @click="openCreate">新增数据源</el-button>
      </div>
      <div class="panel-body">
        <el-table :data="datasources" border>
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="dbType" label="类型" width="90" />
          <el-table-column prop="host" label="Host" min-width="150" />
          <el-table-column prop="port" label="端口" width="90" />
          <el-table-column prop="databaseName" label="数据库" min-width="140" />
          <el-table-column prop="username" label="用户" width="120" />
          <el-table-column label="操作" width="250">
            <template #default="{ row }">
              <el-button link type="primary" @click="test(row)">测试连接</el-button>
              <el-button link type="primary" @click="refresh(row)">刷新元数据</el-button>
              <el-button link @click="openGrant(row)">授权</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="新增数据源" width="560px">
      <el-form label-position="top">
        <el-form-item label="数据库类型">
          <el-select v-model="form.dbType" style="width: 100%">
            <el-option label="MySQL" value="MYSQL" />
          </el-select>
        </el-form-item>
        <el-form-item label="Host"><el-input v-model="form.host" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password /></el-form-item>
        <el-form-item label="数据库">
          <el-select
            v-model="form.databaseName"
            filterable
            :loading="loadingDatabases"
            :disabled="!connectionReady || loadingDatabases"
            style="width: 100%"
          >
            <el-option v-for="db in databaseOptions" :key="db" :label="db" :value="db" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="create">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="grantVisible" title="授权用户" width="420px">
      <el-select v-model="grantUserId" placeholder="选择用户" style="width: 100%">
        <el-option v-for="u in users" :key="u.id" :label="`${u.displayName} (${u.username})`" :value="u.id" />
      </el-select>
      <template #footer>
        <el-button @click="grantVisible = false">取消</el-button>
        <el-button type="primary" @click="grant">授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const datasources = ref<any[]>([])
const users = ref<any[]>([])
const dialogVisible = ref(false)
const grantVisible = ref(false)
const currentDatasource = ref<any>()
const grantUserId = ref<number>()
const databaseOptions = ref<string[]>([])
const loadingDatabases = ref(false)
const form = reactive({ dbType: 'MYSQL', name: '', host: '', port: 3306, databaseName: '', username: '', password: '', remark: '' })
let loadTimer: ReturnType<typeof setTimeout> | undefined
let latestDatabaseLoadKey = ''

const connectionReady = computed(() =>
  !!form.dbType &&
  !!form.host.trim() &&
  !!form.port &&
  !!form.username.trim() &&
  !!form.password
)

onMounted(load)

watch(
  () => [dialogVisible.value, form.dbType, form.host, form.port, form.username, form.password],
  () => {
    if (!dialogVisible.value) {
      if (loadTimer) clearTimeout(loadTimer)
      latestDatabaseLoadKey = ''
      loadingDatabases.value = false
      return
    }
    form.databaseName = ''
    databaseOptions.value = []
    latestDatabaseLoadKey = ''
    loadingDatabases.value = false
    if (loadTimer) clearTimeout(loadTimer)
    if (!connectionReady.value) return
    loadTimer = setTimeout(loadDatabases, 500)
  }
)

async function load() {
  datasources.value = await api.get('/datasources') as any[]
  try { users.value = await api.get('/datasources/users') as any[] } catch {}
}

function openCreate() {
  Object.assign(form, { dbType: 'MYSQL', name: '', host: '', port: 3306, databaseName: '', username: '', password: '', remark: '' })
  databaseOptions.value = []
  latestDatabaseLoadKey = ''
  dialogVisible.value = true
}

async function loadDatabases() {
  const payload = {
    dbType: form.dbType,
    host: form.host.trim(),
    port: form.port,
    username: form.username.trim(),
    password: form.password
  }
  const key = JSON.stringify(payload)
  latestDatabaseLoadKey = key
  loadingDatabases.value = true
  try {
    const data: any = await api.post('/datasources/databases', payload)
    if (latestDatabaseLoadKey !== key) return
    databaseOptions.value = data.databases || []
    if (!databaseOptions.value.length) {
      ElMessage.warning('未获取到可用数据库')
    }
  } catch (e: any) {
    if (latestDatabaseLoadKey === key) {
      databaseOptions.value = []
      ElMessage.error(e.message || '数据库列表加载失败')
    }
  } finally {
    if (latestDatabaseLoadKey === key) {
      loadingDatabases.value = false
    }
  }
}

async function create() {
  if (!validateForm()) return
  await api.post('/datasources', {
    dbType: form.dbType,
    name: form.name.trim(),
    host: form.host.trim(),
    port: form.port,
    databaseName: form.databaseName,
    username: form.username.trim(),
    password: form.password,
    remark: form.remark.trim()
  })
  dialogVisible.value = false
  databaseOptions.value = []
  await load()
  ElMessage.success('数据源已创建')
}

function validateForm() {
  const checks = [
    { value: form.dbType, label: '数据库类型' },
    { value: form.host.trim(), label: 'Host' },
    { value: form.port, label: '端口' },
    { value: form.username.trim(), label: '用户名' },
    { value: form.password, label: '密码' },
    { value: form.databaseName, label: '数据库' },
    { value: form.name.trim(), label: '名称' }
  ]
  const missing = checks.find(item => !item.value)
  if (missing) {
    ElMessage.warning(`${missing.label}不能为空`)
    return false
  }
  if (form.dbType !== 'MYSQL') {
    ElMessage.warning('第一版仅支持 MySQL')
    return false
  }
  return true
}

async function test(row: any) {
  const data: any = await api.post(`/datasources/${row.id}/test`)
  data.success ? ElMessage.success(`连接成功，耗时 ${data.durationMs}ms`) : ElMessage.error(data.message)
}

async function refresh(row: any) {
  const data: any = await api.post(`/datasources/${row.id}/metadata/refresh`)
  ElMessage.success(`已同步 ${data.tableCount} 张表，${data.columnCount} 个字段`)
}

function openGrant(row: any) {
  currentDatasource.value = row
  grantVisible.value = true
}

async function grant() {
  await api.post(`/datasources/${currentDatasource.value.id}/grants/${grantUserId.value}`)
  grantVisible.value = false
  ElMessage.success('授权成功')
}
</script>
