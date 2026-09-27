package com.example.starter.api.dto;

/**
 * 气象不达标明细。actualValue 为预测实际值，requiredValue 为标准要求值，
 * deficit = requiredValue - actualValue（正数表示低于标准的差额），单位米。
 *
 * @param zoneId        区域标识
 * @param effectiveFrom 标准时段起点（含），epoch 毫秒（UTC）
 * @param effectiveTo   标准时段终点（不含），epoch 毫秒（UTC）
 * @param metric        指标：VISIBILITY 能见度 / CLOUD_BASE 云底
 * @param actualValue   预测实际值，单位米
 * @param requiredValue 最低标准要求值，单位米
 * @param deficit       差额（要求值 - 实际值），单位米
 */
public record WeatherViolationDto(
        String zoneId,
        Long effectiveFrom,
        Long effectiveTo,
        String metric,
        Integer actualValue,
        Integer requiredValue,
        Integer deficit) {
}
