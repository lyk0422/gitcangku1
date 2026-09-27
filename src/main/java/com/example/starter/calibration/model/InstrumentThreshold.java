package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器不确定度阈值（版本化）。每次登记生成递增版本，历史版本保留。
 *
 * @param id           阈值记录 ID（自增）
 * @param instrumentId 仪器 ID
 * @param threshold    合成不确定度上限（含端点），非负
 * @param version      阈值版本号，同一仪器自 1 递增
 * @param createdBy    登记人（X-Actor-Id）
 * @param requestId    本次登记的幂等请求 ID
 * @param createdAt    登记时间（UTC）
 */
public record InstrumentThreshold(
        long id,
        String instrumentId,
        BigDecimal threshold,
        int version,
        String createdBy,
        String requestId,
        Instant createdAt) {
}
