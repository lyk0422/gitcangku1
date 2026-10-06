package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 仪器分辨率格点配置响应。
 *
 * @param instrumentId 仪器 ID
 * @param step         分辨率步长 s（十进制字符串）
 * @param origin       格点原点 o（十进制字符串）
 * @param version      当前配置版本（从 1 起）
 * @param updatedBy    最近配置人
 * @param updatedAt    最近更新时间（UTC）
 */
public record ResolutionConfigResponse(
        String instrumentId,
        String step,
        String origin,
        int version,
        String updatedBy,
        Instant updatedAt) {
}
