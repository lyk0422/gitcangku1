package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器分辨率格点配置响应。
 *
 * @param instrumentId 仪器 ID
 * @param version      配置版本，从 1 起
 * @param origin       格点原点 o（十进制字符串）
 * @param step         格点步长 s（十进制字符串，正数）
 * @param updatedBy    最近一次配置操作人
 * @param updatedAt    最近一次配置时间（UTC）
 */
public record ResolutionResponse(
        String instrumentId,
        int version,
        String origin,
        String step,
        String updatedBy,
        Instant updatedAt) {
}
