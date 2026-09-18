<template>
  <div class="page">
    <h2 class="page-title">义务 CSV 导入</h2>
    <p class="page-desc">批量导入应付义务；合法行以 OPEN 状态写入，问题行会被标出且不会入库</p>

    <el-alert
      v-if="!auth.isOperator"
      type="warning"
      :closable="false"
      title="仅操作员可以导入义务，当前为只读账号"
      style="margin-bottom:16px"
    />

    <div class="card-panel">
      <div class="toolbar" style="justify-content:space-between">
        <div>
          <strong>CSV 格式</strong>
          <span class="mono" style="margin-left:8px;color:var(--muted)">
            payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate
          </span>
          <div style="color:var(--muted);font-size:12px;margin-top:4px">
            首行表头可选；日期格式 YYYY-MM-DD；金额必须为正数；付款方与收款方须为不同的有效（未停牌）会员。
          </div>
        </div>
        <el-button @click="downloadTemplate">下载模板</el-button>
      </div>

      <el-upload
        drag
        action=""
        accept=".csv,text/csv"
        :show-file-list="false"
        :disabled="!auth.isOperator || uploading"
        :http-request="uploadFile"
      >
        <el-icon class="el-icon--upload" style="font-size:40px;color:#909399"><upload-filled /></el-icon>
        <div class="el-upload__text">将 CSV 文件拖到此处，或<em>点击上传</em></div>
      </el-upload>
      <div v-if="lastFileName" style="margin-top:8px;color:var(--muted)">
        最近文件：<span class="mono">{{ lastFileName }}</span>
      </div>
    </div>

    <div v-if="result" class="card-panel" style="margin-top:16px">
      <div class="toolbar" style="justify-content:space-between">
        <div class="summary">
          <el-tag type="info" size="large">共 {{ result.totalRows }} 行</el-tag>
          <el-tag type="success" size="large">成功 {{ result.successCount }} 条</el-tag>
          <el-tag :type="result.failureCount > 0 ? 'danger' : 'info'" size="large">
            失败 {{ result.failureCount }} 条
          </el-tag>
        </div>
        <el-button type="primary" @click="$router.push('/obligations')">前往义务列表查看</el-button>
      </div>

      <el-alert
        v-if="result.failureCount === 0"
        type="success"
        :closable="false"
        title="全部行导入成功，可在义务列表（OPEN 状态）中查看"
        style="margin-top:8px"
      />
      <el-alert
        v-else
        type="error"
        :closable="false"
        :title="`${result.successCount} 行已写入 OPEN，以下 ${result.failureCount} 行有问题未导入`"
        style="margin-top:8px"
      />

      <el-table
        v-if="result.errors.length"
        :data="result.errors"
        row-class-name="error-row"
        stripe
        style="margin-top:12px"
      >
        <el-table-column prop="lineNumber" label="问题行号" width="100">
          <template #default="{ row }">第 {{ row.lineNumber }} 行</template>
        </el-table-column>
        <el-table-column prop="rawLine" label="原始内容" min-width="320">
          <template #default="{ row }"><span class="mono">{{ row.rawLine }}</span></template>
        </el-table-column>
        <el-table-column prop="reason" label="问题原因" min-width="220" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import api from '../api/client'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const uploading = ref(false)
const lastFileName = ref('')
const result = ref(null)

const TEMPLATE_HEADER = 'payerMemberId,payeeMemberId,currency,amount,tradeDate,settleDate'

async function uploadFile({ file }) {
  uploading.value = true
  lastFileName.value = file.name
  try {
    const form = new FormData()
    form.append('file', file)
    const { data } = await api.post('/obligations/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    result.value = data
    if (data.failureCount > 0) {
      ElMessage.warning(`成功 ${data.successCount} 条，失败 ${data.failureCount} 条`)
    } else {
      ElMessage.success(`成功导入 ${data.successCount} 条义务`)
    }
  } finally {
    uploading.value = false
  }
}

function downloadTemplate() {
  const blob = new Blob([TEMPLATE_HEADER + '\n'], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'obligations-template.csv'
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.summary {
  display: flex;
  gap: 10px;
  align-items: center;
}
:deep(.error-row td) {
  background: #fef0f0 !important;
}
</style>
