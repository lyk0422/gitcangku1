package com.example.starter.calibration.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准证书。创建后不可修改，只能撤销。
 *
 * @param id           证书主键 ID
 * @param instrumentId 仪器 ID
 * @param validFrom    UTC 有效起点（含，左闭）
 * @param validTo      UTC 有效终点（不含，右开）
 * @param coefficientA 校准系数 a
 * @param offsetB      校准偏移 b
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时刻（UTC），未撤销为 null
 * @param createdAt    创建时刻（UTC）
 */
public record Certificate(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal coefficientA,
        BigDecimal offsetB,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 判断给定时刻是否落在本证书有效区间 [validFrom, validTo) 内。
     */
    public boolean covers(Instant instant) {
        return !instant.isBefore(validFrom) && instant.isBefore(validTo);
    }

    /**
     * 判断本证书区间与另一区间是否重叠（相邻端点相等不算重叠，因右开）。
     */
    public boolean overlaps(Instant otherFrom, Instant otherTo) {
        return validFrom.isBefore(otherTo) && otherFrom.isBefore(validTo);
    }
}
