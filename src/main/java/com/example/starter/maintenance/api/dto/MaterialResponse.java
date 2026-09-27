package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 材料批次台账视图。
 *
 * @param batchCode    材料批次编码
 * @param status       ACTIVE 或 REVOKED
 * @param description  材料描述（null 表示登记时未提供）
 * @param revokeReason 撤销原因（null 表示未撤销或未填写原因）
 * @param createdAt    登记时刻（UTC）
 * @param revokedAt    撤销时刻（UTC，未撤销时为 null）
 */
public record MaterialResponse(
        String batchCode,
        String status,
        String description,
        String revokeReason,
        Instant createdAt,
        Instant revokedAt) {
}
