<template>
  <div class="panel">
    <div class="panel-header">查询历史</div>
    <div class="panel-body">
      <el-table :data="history" border height="650">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column prop="dataSourceName" label="数据源" min-width="150" />
        <el-table-column prop="modelName" label="模型" min-width="130" />
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="failureReason" label="失败原因" width="150" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="110" />
        <el-table-column prop="costEstimate" label="成本" width="100" />
        <el-table-column prop="feedbackScore" label="评分" width="90" />
        <el-table-column label="详情" width="90">
          <template #default="{ row }"><el-button link type="primary" @click="show(row)">查看</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="visible" title="查询详情" width="760px">
      <h4>问题</h4>
      <p>{{ current?.question }}</p>
      <h4>SQL</h4>
      <pre>{{ current?.finalSql || current?.generatedSql }}</pre>
      <h4>流程</h4>
      <el-timeline>
        <el-timeline-item v-for="item in trace" :key="item.name" :type="item.status === 'error' ? 'danger' : item.status">
          <strong>{{ item.name }}</strong>
          <div class="muted">{{ item.message }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'

const history = ref<any[]>([])
const visible = ref(false)
const current = ref<any>()
const trace = computed(() => {
  try { return JSON.parse(current.value?.traceJson || '[]') } catch { return [] }
})

onMounted(async () => {
  history.value = await api.get('/history') as any[]
})

function show(row: any) {
  current.value = row
  visible.value = true
}
</script>

<style scoped>
pre { white-space: pre-wrap; background: #f7f9fc; border: 1px solid #e4ebf5; border-radius: 8px; padding: 12px; }
</style>
