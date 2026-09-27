package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器不确定度阈值视图。
 *
 * @param id           阈值记录 ID
 * @param instrumentId 仪器 ID
 * @param threshold    阈值取值（十进制字符串）
 * @param version      阈值版本号
 * @param createdBy    登记人
 * @param requestId    登记的幂等请求 ID
 * @param createdAt    登记时间（UTC）
 */
public record ThresholdView(long id, String instrumentId, String threshold, int version,
                            String createdBy, String requestId, Instant createdAt) {
}
