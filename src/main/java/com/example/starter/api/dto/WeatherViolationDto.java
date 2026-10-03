package com.example.starter.api.dto;

/**
 * 气象违规明细：某气象区域某 UTC 时段内，某项预测值低于最低标准。
 *
 * @param regionId       气象区域标识
 * @param windowStartUtc 时段起点（epoch 毫秒，UTC，左闭）
 * @param windowEndUtc   时段终点（epoch 毫秒，UTC，右开）
 * @param metric         指标：VISIBILITY 能见度 / CLOUD_BASE 云底
 * @param actualM        预测实际值（米）
 * @param requiredM      最低标准要求值（米）
 * @param deficitM       差额（要求值 - 实际值，米，正数表示低于标准的量）
 */
public record WeatherViolationDto(
        String regionId,
        long windowStartUtc,
        long windowEndUtc,
        String metric,
        int actualM,
        int requiredM,
        int deficitM) {
}