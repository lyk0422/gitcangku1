package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 移交令记录。
 *
 * @param id            主键
 * @param orderVersion  移交令版本，全局唯一
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键
 * @param status        令状态：VALID 有效 / REVOKED 已撤销（失效）
 * @param createdBy     登记操作人
 * @param createdAt     登记时间（UTC）
 * @param revokedAt     撤销时间（UTC）；NULL 表示仍有效
 */
public record TransferOrder(
        long id,
        String orderVersion,
        String sourceCaseKey,
        String targetCaseKey,
        TransferOrderStatus status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
