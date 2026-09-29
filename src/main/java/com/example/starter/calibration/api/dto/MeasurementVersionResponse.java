package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 测量版本摘要项（版本历史查询）。字段、时间精度与 null 语义与测量明细保持稳定。
 *
 * @param id             测量记录（版本行）ID
 * @param version        版本号
 * @param status         状态：PENDING / RELEASED / SUPERSEDED
 * @param measuredAt     测量时刻（UTC）
 * @param computedValue  未舍入计算值（十进制字符串）
 * @param passed         偏差判定
 * @param createdAt      该版本创建时间（UTC）
 */
public record MeasurementVersionResponse(
        long id,
        int version,
        String status,
        Instant measuredAt,
        String computedValue,
        boolean passed,
        Instant createdAt) {
}
