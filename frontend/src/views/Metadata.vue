<template>
  <div class="panel">
    <div class="panel-header">
      <span>元数据浏览</span>
      <el-select v-model="dataSourceId" placeholder="选择数据源" style="width: 260px" @change="loadMetadata">
        <el-option v-for="ds in datasources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
    </div>
    <div class="panel-body metadata-layout">
      <el-tree :data="treeData" node-key="id" default-expand-all :props="{ label: 'label', children: 'children' }" @node-click="selectNode" />
      <div>
        <h3>{{ selectedTable?.tableName || '请选择表' }}</h3>
        <p class="muted">{{ selectedTable?.tableComment }}</p>
        <el-table :data="selectedTable?.columns || []" border height="520">
          <el-table-column prop="columnName" label="字段" min-width="160" />
          <el-table-column prop="dataType" label="类型" width="140" />
          <el-table-column prop="nullableFlag" label="可空" width="90" />
          <el-table-column prop="columnKey" label="键" width="90" />
          <el-table-column prop="columnComment" label="注释" min-width="180" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'

const datasources = ref<any[]>([])
const dataSourceId = ref<number>()
const metadata = ref<any>({ tables: [] })
const treeData = ref<any[]>([])
const selectedTable = ref<any>()

onMounted(async () => {
  datasources.value = await api.get('/datasources') as any[]
  dataSourceId.value = datasources.value[0]?.id
  await loadMetadata()
})

async function loadMetadata() {
  if (!dataSourceId.value) return
  metadata.value = await api.get(`/datasources/${dataSourceId.value}/metadata`)
  treeData.value = (metadata.value.tables || []).map((table: any) => ({
    id: table.tableName,
    label: `${table.tableName} (${table.columns?.length || 0})`,
    raw: table
  }))
  selectedTable.value = metadata.value.tables?.[0]
}

function selectNode(node: any) {
  selectedTable.value = node.raw
}
</script>

<style scoped>
.metadata-layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 16px; }
</style>
