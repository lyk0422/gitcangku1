package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 材料批次视图。
 *
 * @param materialId    材料批次号
 * @param materialName  材料名称
 * @param unit          计量单位
 * @param status        批次状态：ACTIVE / REVOKED
 * @param registeredAt  登记时刻（UTC）
 * @param revokedAt     撤销时刻（UTC）；未撤销为 null
 * @param revokeReason  撤销原因；未撤销为 null
 */
public record MaterialResponse(
        String materialId,
        String materialName,
        String unit,
        String status,
        Instant registeredAt,
        Instant revokedAt,
        String revokeReason) {
}
