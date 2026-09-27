package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器不确定度阈值版本响应。
 *
 * @param id             阈值版本记录 ID（放行快照以此 ID 固化）
 * @param instrumentId   仪器 ID
 * @param version        仪器内版本号
 * @param validFrom      有效期起点（UTC，含）
 * @param validTo        有效期终点（UTC，不含）；开放区间为 null
 * @param maxUncertainty 合成不确定度上限阈值（十进制字符串）
 * @param createdAt      创建时间（UTC）
 */
public record ThresholdResponse(
        long id,
        String instrumentId,
        long version,
        Instant validFrom,
        Instant validTo,
        String maxUncertainty,
        Instant createdAt) {
}
