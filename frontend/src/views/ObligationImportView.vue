<template>
  <div class="page">
    <h2 class="page-title">义务 CSV 导入</h2>
    <p class="page-desc">上传义务 CSV，系统逐行校验：有效行写入 OPEN，问题行在下方标出</p>

    <el-alert
      v-if="!auth.isOperator"
      type="warning"
      :closable="false"
      title="仅操作员可导入 CSV"
      description="当前为只读账号，只能查看本页面，无法执行导入。"
      style="margin-bottom:16px"
    />

    <div class="card-panel" style="margin-bottom:16px">
      <el-upload
        drag
        accept=".csv,text/csv"
        :show-file-list="false"
        :http-request="doUpload"
        :disabled="!auth.isOperator || uploading"
      >
        <el-icon class="is-icon" :size="40"><upload-filled /></el-icon>
        <div class="el-upload__text">将 CSV 文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">
            仅支持 .csv 文件（上限 10MB），表头：
            <span class="mono">payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate</span>
            ，日期格式 YYYY-MM-DD
          </div>
        </template>
      </el-upload>
      <div style="margin-top:12px;display:flex;gap:12px">
        <el-button @click="downloadTemplate">下载 CSV 模板</el-button>
      </div>
    </div>

    <template v-if="result">
      <div class="summary-bar">
        <el-tag size="large" type="info">总行数 {{ result.totalRows }}</el-tag>
        <el-tag size="large" type="success">成功 {{ result.successCount }} 条</el-tag>
        <el-tag size="large" type="danger">失败 {{ result.failureCount }} 条</el-tag>
        <el-button
          v-if="result.successCount > 0"
          type="primary"
          plain
          style="margin-left:auto"
          @click="goToList"
        >在义务列表中查看新导入的义务</el-button>
      </div>

      <div class="card-panel">
        <el-table :data="result.rows" :row-class-name="rowClass" stripe>
          <el-table-column prop="lineNumber" label="CSV 行号" width="90" />
          <el-table-column prop="payerMemberId" label="付款方" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.payerMemberId || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="payeeMemberId" label="收款方" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.payeeMemberId || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="currency" label="币种" width="80">
            <template #default="{ row }">{{ row.currency || '—' }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="120">
            <template #default="{ row }">{{ row.amount || '—' }}</template>
          </el-table-column>
          <el-table-column prop="tradeDate" label="交易日" width="120">
            <template #default="{ row }">{{ row.tradeDate || '—' }}</template>
          </el-table-column>
          <el-table-column prop="settleDate" label="交割日" width="120">
            <template #default="{ row }">{{ row.settleDate || '—' }}</template>
          </el-table-column>
          <el-table-column label="结果" min-width="220">
            <template #default="{ row }">
              <el-tag v-if="row.success" type="success">成功</el-tag>
              <span v-else class="error-text">
                <el-tag type="danger" style="margin-right:6px">失败</el-tag>{{ row.error }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const uploading = ref(false)
const result = ref(null)
const members = ref([])

async function doUpload({ file }) {
  if (!file.name.toLowerCase().endsWith('.csv')) {
    ElMessage.error('请选择 .csv 文件')
    return
  }
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const { data } = await api.post('/obligations/import', form)
    result.value = data
    if (data.failureCount > 0) {
      ElMessage.warning(`导入完成：成功 ${data.successCount} 条，失败 ${data.failureCount} 条`)
    } else {
      ElMessage.success(`导入完成：成功 ${data.successCount} 条`)
    }
  } finally {
    uploading.value = false
  }
}

function rowClass({ row }) {
  return row.success ? 'import-row-success' : 'import-row-fail'
}

function goToList() {
  router.push({ name: 'obligations', query: { status: 'OPEN' } })
}

function downloadTemplate() {
  const active = members.value.filter((m) => m.status === 'ACTIVE')
  const payer = active[0]?.memberId || 'PAYER_MEMBER_ID'
  const payee = active[1]?.memberId || 'PAYEE_MEMBER_ID'
  const today = new Date().toISOString().slice(0, 10)
  const csv =
    'payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate\n' +
    `${payer},${payee},USD,10000,${today},${today}\n`
  // 加 UTF-8 BOM，Excel 直接打开不乱码
  const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'obligation-import-template.csv'
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(async () => {
  try {
    const { data } = await api.get('/members')
    members.value = data
  } catch {
    // 模板回退为占位 ID，不阻塞页面
  }
})
</script>

<style scoped>
.summary-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}
.is-icon {
  color: var(--accent);
}
.error-text {
  color: #c45656;
  font-size: 13px;
}
:deep(.import-row-fail) {
  background-color: #fef0f0;
}
:deep(.import-row-fail:hover > td) {
  background-color: #fde2e2 !important;
}
:deep(.import-row-success) {
  background-color: #f0f9eb;
}
</style>
