package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器不确定度阈值版本响应。
 *
 * @param id             阈值版本行 ID
 * @param instrumentId   仪器 ID
 * @param version        仪器内版本号，从 1 起
 * @param maxUncertainty 合成不确定度上限（含端点，十进制字符串）
 * @param createdBy      发布人
 * @param createdAt      发布时间（UTC）
 */
public record ThresholdResponse(
        long id,
        String instrumentId,
        int version,
        String maxUncertainty,
        String createdBy,
        Instant createdAt) {
}
