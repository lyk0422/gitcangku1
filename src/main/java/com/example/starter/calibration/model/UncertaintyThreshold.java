package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器不确定度阈值版本。同一仪器版本从 1 递增，旧版本永不改写。
 *
 * @param id             阈值记录 ID（自增）
 * @param instrumentId   仪器 ID
 * @param version        阈值版本
 * @param maxUncertainty 合成不确定度上限（含端点），非负
 * @param createdBy      阈值设置人
 * @param createdAt      创建时间（UTC）
 */
public record UncertaintyThreshold(
        long id,
        String instrumentId,
        int version,
        BigDecimal maxUncertainty,
        String createdBy,
        Instant createdAt) {
}
