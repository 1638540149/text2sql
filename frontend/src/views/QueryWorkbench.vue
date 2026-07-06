<template>
  <div class="workbench">
    <div class="panel">
      <div class="panel-header">数据源</div>
      <div class="panel-body stack">
        <el-select v-model="dataSourceId" placeholder="选择数据源" @change="loadMetadata">
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
        <el-select v-model="modelId" placeholder="选择模型">
          <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
        </el-select>
        <el-button @click="refreshMetadata">刷新元数据</el-button>
        <el-tree
          :data="treeData"
          node-key="id"
          show-checkbox
          default-expand-all
          :props="{ label: 'label', children: 'children' }"
          @check="onTreeCheck"
        />
      </div>
    </div>

    <div class="stack">
      <div class="panel">
        <div class="panel-header">自然语言问题</div>
        <div class="panel-body stack">
          <el-input v-model="question" type="textarea" :rows="4" maxlength="1000" show-word-limit />
          <div class="toolbar">
            <el-button type="primary" :loading="running" @click="run(false)">生成并执行</el-button>
            <el-button @click="clearAll">清空</el-button>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header">
          <span>生成的 SQL</span>
          <el-button type="primary" link :disabled="!sql" @click="run(true)">按当前 SQL 运行</el-button>
        </div>
        <div class="panel-body">
          <textarea v-model="sql" class="sql-editor" />
          <el-alert v-if="needsConfirmation" type="warning" :closable="false" show-icon>
            <template #title>EXPLAIN 检查提示高风险，确认后可继续执行。</template>
            <el-button type="warning" size="small" @click="confirmRisk">确认执行</el-button>
          </el-alert>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header">
          <span>查询结果</span>
          <div class="toolbar">
            <el-button :disabled="!sql" @click="downloadSql">导出 SQL</el-button>
            <el-button :disabled="!rows.length" @click="downloadCsv">导出 CSV</el-button>
            <el-button :disabled="!historyId" @click="feedbackVisible = true">评分</el-button>
          </div>
        </div>
        <div class="panel-body">
          <el-tabs v-model="resultTab">
            <el-tab-pane label="表格" name="table">
              <el-table :data="rows" height="310" border>
                <el-table-column v-for="field in fields" :key="field.name" :prop="field.name" :label="field.name" min-width="130" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="chart?.title || '图表'" name="chart">
              <div ref="chartEl" class="chart-box" />
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </div>

    <div class="panel trace-panel">
      <div class="panel-header">执行流程</div>
      <div class="panel-body">
        <el-timeline>
          <el-timeline-item v-for="item in trace" :key="item.name" :type="timelineType(item.status)" :timestamp="item.status">
            <strong>{{ item.name }}</strong>
            <div class="muted">{{ item.message }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </div>

    <el-dialog v-model="feedbackVisible" title="结果评分" width="420px">
      <el-rate v-model="feedback.score" />
      <el-select v-model="feedback.tags" multiple placeholder="问题标签" style="width: 100%; margin-top: 16px">
        <el-option label="SQL错误" value="SQL错误" />
        <el-option label="结果不准" value="结果不准" />
        <el-option label="图表不合适" value="图表不合适" />
        <el-option label="解释不清楚" value="解释不清楚" />
      </el-select>
      <el-input v-model="feedback.comment" type="textarea" :rows="3" placeholder="可选评论" style="margin-top: 12px" />
      <template #footer>
        <el-button @click="feedbackVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFeedback">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { api } from '../api/client'

const datasources = ref<any[]>([])
const models = ref<any[]>([])
const metadata = ref<any>({ tables: [] })
const dataSourceId = ref<number>()
const modelId = ref<number>()
const selectedTables = ref<string[]>([])
const question = ref('按地区统计销售金额，按金额倒序排列')
const sql = ref('')
const fields = ref<any[]>([])
const rows = ref<any[]>([])
const chart = ref<any>()
const trace = ref<any[]>([])
const running = ref(false)
const needsConfirmation = ref(false)
const historyId = ref<number>()
const resultTab = ref('table')
const chartEl = ref<HTMLDivElement>()
const feedbackVisible = ref(false)
const feedback = reactive({ score: 5, tags: [] as string[], comment: '' })

const treeData = ref<any[]>([])

onMounted(async () => {
  await Promise.all([loadDatasources(), loadModels()])
})

async function loadDatasources() {
  datasources.value = await api.get('/datasources') as any[]
  dataSourceId.value = datasources.value[0]?.id
  if (dataSourceId.value) await loadMetadata()
}

async function loadModels() {
  models.value = await api.get('/models') as any[]
  modelId.value = models.value[0]?.id
}

async function loadMetadata() {
  if (!dataSourceId.value) return
  metadata.value = await api.get(`/datasources/${dataSourceId.value}/metadata`)
  treeData.value = (metadata.value.tables || []).map((table: any) => ({
    id: table.tableName,
    label: table.tableName,
    children: (table.columns || []).map((c: any) => ({ id: `${table.tableName}.${c.columnName}`, label: `${c.columnName} · ${c.dataType}` }))
  }))
}

async function refreshMetadata() {
  if (!dataSourceId.value) return
  await api.post(`/datasources/${dataSourceId.value}/metadata/refresh`)
  await loadMetadata()
  ElMessage.success('元数据已刷新')
}

function onTreeCheck(_: any, state: any) {
  selectedTables.value = state.checkedKeys.filter((key: string) => !key.includes('.'))
}

async function run(useEditedSql: boolean) {
  if (!dataSourceId.value || !modelId.value) return ElMessage.warning('请选择数据源和模型')
  running.value = true
  needsConfirmation.value = false
  try {
    const data: any = await api.post('/query/run', {
      dataSourceId: dataSourceId.value,
      modelId: modelId.value,
      question: question.value,
      selectedTables: selectedTables.value,
      editedSql: useEditedSql ? sql.value : '',
      confirmHighRisk: false
    })
    handleResult(data)
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    running.value = false
  }
}

async function confirmRisk() {
  const data: any = await api.post('/query/run', {
    dataSourceId: dataSourceId.value,
    modelId: modelId.value,
    question: question.value,
    selectedTables: selectedTables.value,
    editedSql: sql.value,
    confirmHighRisk: true
  })
  handleResult(data)
}

function handleResult(data: any) {
  historyId.value = data.historyId
  trace.value = data.trace || []
  sql.value = data.sql || sql.value
  needsConfirmation.value = !!data.needsConfirmation
  if (data.success) {
    fields.value = data.fields || []
    rows.value = data.rows || []
    chart.value = data.chart
    nextTick(renderChart)
  } else if (data.message) {
    ElMessage.warning(data.message)
  }
}

function renderChart() {
  if (!chartEl.value || !chart.value?.option) return
  echarts.getInstanceByDom(chartEl.value)?.dispose()
  echarts.init(chartEl.value).setOption(chart.value.option)
}

async function submitFeedback() {
  if (!historyId.value) return
  await api.post(`/history/${historyId.value}/feedback`, feedback)
  feedbackVisible.value = false
  ElMessage.success('感谢反馈')
}

function timelineType(status: string) {
  return status === 'error' ? 'danger' : status === 'warning' ? 'warning' : 'success'
}

function clearAll() {
  sql.value = ''
  fields.value = []
  rows.value = []
  trace.value = []
  chart.value = undefined
}

function downloadSql() {
  download('query.sql', sql.value)
}

function downloadCsv() {
  const header = fields.value.map(f => f.name).join(',')
  const body = rows.value.map(row => fields.value.map(f => JSON.stringify(row[f.name] ?? '')).join(',')).join('\n')
  download('result.csv', `${header}\n${body}`)
}

function download(name: string, content: string) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}
</script>
