package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器不确定度阈值版本。阈值只增不改：发布修订即产生新版本，旧预算与旧放行继续引用旧版本。
 *
 * @param id             阈值版本行 ID（自增）
 * @param instrumentId   仪器 ID
 * @param version        仪器内版本号，从 1 起逐版递增，不回用
 * @param maxUncertainty 合成不确定度上限（含端点），非负，最多 6 位小数
 * @param createdBy      发布人（X-Actor-Id）
 * @param createdAt      发布时间（UTC）
 */
public record UncertaintyThreshold(
        long id,
        String instrumentId,
        int version,
        BigDecimal maxUncertainty,
        String createdBy,
        Instant createdAt) {
}
