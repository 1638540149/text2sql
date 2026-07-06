<template>
  <div class="stack">
    <div class="panel">
      <div class="panel-header">
        <span>模型结果分析</span>
        <div class="toolbar">
          <el-select v-model="modelId" clearable placeholder="模型" style="width: 180px">
            <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <el-select v-model="dataSourceId" clearable placeholder="数据源" style="width: 200px">
            <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button type="primary" @click="load">刷新</el-button>
        </div>
      </div>
    </div>

    <div class="metric-grid">
      <div class="metric-card"><div class="metric-label">调用次数</div><div class="metric-value">{{ summary.total }}</div></div>
      <div class="metric-card"><div class="metric-label">成功率</div><div class="metric-value">{{ percent(summary.successRate) }}</div></div>
      <div class="metric-card"><div class="metric-label">成功次数</div><div class="metric-value">{{ summary.success }}</div></div>
      <div class="metric-card"><div class="metric-label">模型数量</div><div class="metric-value">{{ analytics.models?.length || 0 }}</div></div>
    </div>

    <div class="analytics-grid">
      <div class="panel">
        <div class="panel-header">成功率趋势</div>
        <div ref="trendEl" class="chart-box" />
      </div>
      <div class="panel">
        <div class="panel-header">失败原因分布</div>
        <div ref="failureEl" class="chart-box" />
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">模型排行</div>
      <div class="panel-body">
        <el-table :data="analytics.models || []" border>
          <el-table-column prop="modelName" label="模型" min-width="160" />
          <el-table-column prop="total" label="调用次数" width="100" />
          <el-table-column label="成功率" width="100"><template #default="{ row }">{{ percent(row.successRate) }}</template></el-table-column>
          <el-table-column prop="avgDurationMs" label="平均耗时" width="110" />
          <el-table-column prop="p95DurationMs" label="P95耗时" width="110" />
          <el-table-column prop="tokens" label="Token" width="110" />
          <el-table-column prop="cost" label="成本" width="110" />
          <el-table-column prop="avgRows" label="平均行数" width="110" />
          <el-table-column prop="avgFeedback" label="评分均值" width="110" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api/client'

const analytics = ref<any>({ summary: {}, models: [], daily: [], failures: [] })
const models = ref<any[]>([])
const datasources = ref<any[]>([])
const modelId = ref<number>()
const dataSourceId = ref<number>()
const trendEl = ref<HTMLDivElement>()
const failureEl = ref<HTMLDivElement>()
const summary = computed(() => analytics.value.summary || {})

onMounted(async () => {
  models.value = await api.get('/models') as any[]
  datasources.value = await api.get('/datasources') as any[]
  await load()
})

async function load() {
  analytics.value = await api.get('/admin/analytics/models', { params: { modelId: modelId.value, dataSourceId: dataSourceId.value } })
  nextTick(render)
}

function render() {
  if (trendEl.value) {
    echarts.getInstanceByDom(trendEl.value)?.dispose()
    const daily = analytics.value.daily || []
    echarts.init(trendEl.value).setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: daily.map((d: any) => d.date) },
      yAxis: { type: 'value', max: 1 },
      series: [{ type: 'line', name: '成功率', data: daily.map((d: any) => d.total ? d.success / d.total : 0) }]
    })
  }
  if (failureEl.value) {
    echarts.getInstanceByDom(failureEl.value)?.dispose()
    echarts.init(failureEl.value).setOption({
      tooltip: { trigger: 'item' },
      series: [{ type: 'pie', radius: '58%', data: analytics.value.failures || [] }]
    })
  }
}

function percent(value: number) {
  return `${Math.round((value || 0) * 100)}%`
}
</script>

<style scoped>
.analytics-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
@media (max-width: 960px) { .analytics-grid { grid-template-columns: 1fr; } }
</style>
