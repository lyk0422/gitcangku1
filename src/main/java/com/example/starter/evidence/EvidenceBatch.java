package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 批量入库批次实体，对应 evidence_batch 表。
 *
 * @param id              主键
 * @param intakeKey       批次业务键（即请求 requestId），全局唯一
 * @param custodianId     批次保管人
 * @param totalCount      批次证物总数
 * @param matchedCount    重量核对 MATCHED 件数
 * @param discrepantCount 重量核对 DISCREPANT 件数
 * @param createdAt       批次入库时间（Asia/Shanghai）
 */
public record EvidenceBatch(
        Long id,
        String intakeKey,
        String custodianId,
        int totalCount,
        int matchedCount,
        int discrepantCount,
        LocalDateTime createdAt) {
}
