package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器不确定度阈值版本响应。
 *
 * @param instrumentId   仪器 ID
 * @param version        阈值版本（同一仪器从 1 递增）
 * @param maxUncertainty 合成不确定度上限（十进制字符串，含端点）
 * @param createdBy      阈值设置人
 * @param createdAt      创建时间（UTC）
 */
public record ThresholdResponse(String instrumentId, int version, String maxUncertainty,
                                String createdBy, Instant createdAt) {
}
