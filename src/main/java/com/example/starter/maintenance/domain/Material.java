package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 材料批次台账行。撤销不可逆，台账行保留不删。
 *
 * @param batchCode      材料批次编码，全局唯一，格式 MAT- 开头加 4-16 位大写字母数字
 * @param active         是否在用：true=ACTIVE，false=REVOKED（已撤销）
 * @param description    材料描述（null 表示登记时未提供）
 * @param revokeReason   撤销原因（null 表示未撤销或未填写原因）
 * @param createdAt      登记时刻（UTC）
 * @param revokedAt      撤销时刻（UTC，未撤销时为 null）
 */
public record Material(
        String batchCode,
        boolean active,
        String description,
        String revokeReason,
        Instant createdAt,
        Instant revokedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";
}
