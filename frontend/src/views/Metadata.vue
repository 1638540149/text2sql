<template>
  <div class="panel">
    <div class="panel-header">
      <span>元数据浏览</span>
      <el-select v-model="dataSourceId" placeholder="选择数据源" style="width: 260px" @change="onDataSourceChange">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
    </div>
    <div class="panel-body metadata-layout">
      <div class="metadata-side stack">
        <div class="table-tools">
          <el-input v-model="tableKeyword" clearable placeholder="搜索表" @keyup.enter="loadTables(1)" @clear="loadTables(1)" />
          <el-button @click="loadTables(1)">搜索</el-button>
        </div>
        <el-table :data="tableItems" border height="520" highlight-current-row v-loading="tableLoading" @row-click="selectTable">
          <el-table-column prop="tableName" label="表名" min-width="160" show-overflow-tooltip />
          <el-table-column prop="columnCount" label="字段" width="70" />
        </el-table>
        <el-pagination
          small
          layout="prev, pager, next"
          :current-page="tablePage"
          :page-size="tablePageSize"
          :total="tableTotal"
          @current-change="loadTables"
        />
      </div>
      <div class="stack">
        <div class="metadata-title">
          <div>
            <h3>{{ selectedTable?.tableName || '请选择表' }}</h3>
            <p class="muted">{{ selectedTable?.tableComment }}</p>
          </div>
          <div class="table-tools column-search">
            <el-input v-model="columnKeyword" clearable placeholder="搜索字段" :disabled="!selectedTable" @keyup.enter="loadColumns(1)" @clear="loadColumns(1)" />
            <el-button :disabled="!selectedTable" @click="loadColumns(1)">搜索</el-button>
          </div>
        </div>
        <el-table :data="columnItems" border height="520" v-loading="columnLoading">
          <el-table-column prop="columnName" label="字段" min-width="160" />
          <el-table-column prop="dataType" label="类型" width="140" />
          <el-table-column prop="nullableFlag" label="可空" width="90" />
          <el-table-column prop="columnKey" label="键" width="90" />
          <el-table-column prop="columnComment" label="注释" min-width="180" />
        </el-table>
        <el-pagination
          small
          layout="prev, pager, next"
          :current-page="columnPage"
          :page-size="columnPageSize"
          :total="columnTotal"
          @current-change="loadColumns"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'

const datasources = ref<any[]>([])
const dataSourceId = ref<number>()
const tableItems = ref<any[]>([])
const tablePage = ref(1)
const tablePageSize = ref(20)
const tableTotal = ref(0)
const tableKeyword = ref('')
const tableLoading = ref(false)
const selectedTable = ref<any>()
const columnItems = ref<any[]>([])
const columnPage = ref(1)
const columnPageSize = ref(50)
const columnTotal = ref(0)
const columnKeyword = ref('')
const columnLoading = ref(false)

onMounted(async () => {
  datasources.value = await api.get('/datasources') as any[]
  dataSourceId.value = datasources.value[0]?.id
  await loadTables(1)
})

async function onDataSourceChange() {
  selectedTable.value = undefined
  columnItems.value = []
  await loadTables(1)
}

async function loadTables(page = tablePage.value) {
  if (!dataSourceId.value) return
  tableLoading.value = true
  try {
    const data: any = await api.get(`/datasources/${dataSourceId.value}/metadata/tables`, {
      params: {
        page,
        pageSize: tablePageSize.value,
        keyword: tableKeyword.value
      }
    })
    tableItems.value = data.items || []
    tablePage.value = data.page || page
    tablePageSize.value = data.pageSize || tablePageSize.value
    tableTotal.value = data.total || 0
    if (!selectedTable.value || !tableItems.value.some(table => table.tableName === selectedTable.value.tableName)) {
      selectedTable.value = tableItems.value[0]
      await loadColumns(1)
    }
  } finally {
    tableLoading.value = false
  }
}

async function selectTable(row: any) {
  selectedTable.value = row
  columnKeyword.value = ''
  await loadColumns(1)
}

async function loadColumns(page = columnPage.value) {
  if (!dataSourceId.value || !selectedTable.value?.tableName) {
    columnItems.value = []
    columnTotal.value = 0
    return
  }
  columnLoading.value = true
  try {
    const tableName = encodeURIComponent(selectedTable.value.tableName)
    const data: any = await api.get(`/datasources/${dataSourceId.value}/metadata/tables/${tableName}/columns`, {
      params: {
        page,
        pageSize: columnPageSize.value,
        keyword: columnKeyword.value
      }
    })
    columnItems.value = data.items || []
    columnPage.value = data.page || page
    columnPageSize.value = data.pageSize || columnPageSize.value
    columnTotal.value = data.total || 0
  } finally {
    columnLoading.value = false
  }
}
</script>

<style scoped>
.metadata-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 16px; }
.metadata-side { min-width: 0; }
.metadata-title { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 12px; align-items: start; }
.metadata-title h3 { margin: 0 0 6px; }
.table-tools { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.column-search { align-self: start; }
</style>
