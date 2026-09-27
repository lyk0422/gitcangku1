package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 材料批次登记。撤销为终态，不可恢复；已撤销批次不可再被证据包引用。
 *
 * @param batchCode    材料批次编码，格式 ^[A-Za-z0-9][A-Za-z0-9-]{2,63}$
 * @param materialName 材料名称
 * @param status       批次状态：ACTIVE 可用 / REVOKED 已撤销
 * @param createdAt    登记时刻（UTC）
 * @param revokedAt    撤销时刻（UTC），未撤销为 null
 * @param revokeReason 撤销原因，未撤销为 null
 */
public record MaterialBatch(String batchCode, String materialName, String status,
                            Instant createdAt, Instant revokedAt, String revokeReason) {
}
