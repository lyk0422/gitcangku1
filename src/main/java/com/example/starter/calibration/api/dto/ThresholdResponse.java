package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器不确定度阈值版本响应。
 *
 * @param id             阈值版本行 ID
 * @param instrumentId   仪器 ID
 * @param version        阈值版本号
 * @param threshold      阈值要求值（非负，十进制字符串）
 * @param validFrom      生效起点（UTC，左闭）
 * @param revoked        是否已停用
 * @param revokedAt      停用时间（UTC），未停用为 null
 * @param createdAt      创建时间（UTC）
 */
public record ThresholdResponse(
        long id,
        String instrumentId,
        int version,
        String threshold,
        Instant validFrom,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {
}
