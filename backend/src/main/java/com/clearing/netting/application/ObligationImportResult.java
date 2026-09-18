package com.clearing.netting.application;

import java.util.List;

/**
 * CSV 义务导入结果：有效行已写入（状态 OPEN），问题行逐条给出原因供页面标注。
 *
 * @param totalRows    数据行总数（不含表头）
 * @param successCount 成功写入条数
 */
public record ObligationImportResult(
        int totalRows,
        int successCount,
        List<RowResult> rows) {

    public int failureCount() {
        return totalRows - successCount;
    }

    /**
     * @param lineNumber CSV 中的行号（含表头，第一条数据行为 2）
     * @param error      失败原因；成功行为 null
     */
    public record RowResult(
            int lineNumber,
            boolean success,
            String payerMemberId,
            String payeeMemberId,
            String currency,
            String amount,
            String tradeDate,
            String settleDate,
            String error) {
    }
}
