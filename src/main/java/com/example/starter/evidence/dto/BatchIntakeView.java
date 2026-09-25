package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量入库批次视图（创建响应与批次清单查询共用）。
 *
 * @param intakeKey        批次键（即请求中的 requestId）
 * @param custodianId      批次保管人
 * @param totalCount       批次证物总数
 * @param matchedCount     重量核对 MATCHED 件数
 * @param discrepantCount  重量核对 DISCREPANT 件数
 * @param pendingReviewCount 当前仍待复核件数
 * @param items            逐件清单及核对/复核状态
 * @param createdAt        批次入库时间（Asia/Shanghai）
 */
public record BatchIntakeView(
        String intakeKey,
        String custodianId,
        int totalCount,
        int matchedCount,
        int discrepantCount,
        int pendingReviewCount,
        List<BatchItemView> items,
        LocalDateTime createdAt) {
}
