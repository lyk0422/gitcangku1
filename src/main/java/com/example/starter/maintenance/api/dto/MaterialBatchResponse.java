package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 材料批次视图。
 *
 * @param batchCode    材料批次编码
 * @param materialName 材料名称
 * @param status       批次状态：ACTIVE 可用 / REVOKED 已撤销
 * @param createdAt    登记时刻（UTC）
 * @param revokedAt    撤销时刻（UTC），未撤销为 null
 * @param revokeReason 撤销原因，未撤销为 null
 */
public record MaterialBatchResponse(String batchCode, String materialName, String status,
                                    Instant createdAt, Instant revokedAt, String revokeReason) {
}
