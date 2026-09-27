package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器不确定度阈值版本。版本只增不改，有效区间为 UTC 左闭右开 [validFrom, validTo)；
 * validTo 为 null 表示开放区间。阈值缺失（时刻无生效版本）时测量不可放行。
 *
 * @param id             阈值版本记录 ID（自增）
 * @param instrumentId   仪器 ID
 * @param version        仪器内版本号，从 1 起自增
 * @param validFrom      有效期起点（UTC，含）
 * @param validTo        有效期终点（UTC，不含）；null 表示开放区间
 * @param maxUncertainty 合成不确定度上限阈值，非负
 * @param createdAt      创建时间（UTC）
 */
public record ThresholdVersion(
        long id,
        String instrumentId,
        long version,
        Instant validFrom,
        Instant validTo,
        BigDecimal maxUncertainty,
        Instant createdAt) {
}
