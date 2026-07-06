<template>
  <div class="stack">
    <div class="panel">
      <div class="panel-header">
        <span>模型配置</span>
        <el-button type="primary" @click="openCreate">新增模型</el-button>
      </div>
      <div class="panel-body">
        <el-table :data="models" border>
          <el-table-column prop="name" label="名称" min-width="150" />
          <el-table-column prop="provider" label="供应商" width="160" />
          <el-table-column prop="baseUrl" label="Base URL" min-width="260" />
          <el-table-column prop="modelName" label="Model" min-width="160" />
          <el-table-column prop="enabled" label="启用" width="90">
            <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="promptPricePer1k" label="输入/1K" width="110" />
          <el-table-column prop="completionPricePer1k" label="输出/1K" width="110" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="testModel(row)">测试</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">查询阈值</div>
      <div class="panel-body">
        <el-table :data="settings" border>
          <el-table-column prop="settingKey" label="配置项" min-width="180" />
          <el-table-column prop="settingValue" label="值" width="180">
            <template #default="{ row }"><el-input v-model="row.settingValue" /></template>
          </el-table-column>
          <el-table-column prop="remark" label="说明" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }"><el-button link type="primary" @click="saveSetting(row)">保存</el-button></template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑模型' : '新增模型'" width="560px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" placeholder="https://api.example.com/v1" /></el-form-item>
        <el-form-item :label="editingId ? 'API Key（留空表示不修改）' : 'API Key'">
          <el-input v-model="form.apiKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="Model"><el-input v-model="form.modelName" /></el-form-item>
        <el-form-item label="输入 token 单价 / 1K"><el-input-number v-model="form.promptPricePer1k" :precision="6" :step="0.000001" /></el-form-item>
        <el-form-item label="输出 token 单价 / 1K"><el-input-number v-model="form.completionPricePer1k" :precision="6" :step="0.000001" /></el-form-item>
        <el-form-item><el-switch v-model="form.enabled" active-text="启用" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const models = ref<any[]>([])
const settings = ref<any[]>([])
const dialogVisible = ref(false)
const editingId = ref<number>()
const form = reactive({ name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, promptPricePer1k: 0, completionPricePer1k: 0 })

onMounted(load)

async function load() {
  models.value = await api.get('/admin/models') as any[]
  settings.value = await api.get('/admin/settings') as any[]
}

function resetForm() {
  editingId.value = undefined
  Object.assign(form, { name: '', baseUrl: '', apiKey: '', modelName: '', enabled: true, promptPricePer1k: 0, completionPricePer1k: 0 })
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: any) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    baseUrl: row.baseUrl,
    apiKey: '',
    modelName: row.modelName,
    enabled: !!row.enabled,
    promptPricePer1k: Number(row.promptPricePer1k || 0),
    completionPricePer1k: Number(row.completionPricePer1k || 0)
  })
  dialogVisible.value = true
}

async function save() {
  if (editingId.value) {
    await api.put(`/admin/models/${editingId.value}`, form)
  } else {
    await api.post('/admin/models', form)
  }
  dialogVisible.value = false
  await load()
  ElMessage.success('模型已保存')
}

async function testModel(row: any) {
  try {
    const data: any = await api.post(`/admin/models/${row.id}/test`)
    if (data.success) {
      ElMessage.success('模型调用成功')
    } else {
      ElMessage.error(data.message || '模型调用失败')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '模型调用失败')
  }
}

async function saveSetting(row: any) {
  await api.put('/admin/settings', { key: row.settingKey, value: row.settingValue })
  ElMessage.success('配置已保存')
}
</script>
