package com.example.starter.api.dto;

/**
 * 气象不达标明细（响应）。实际值、要求值与差额均为实际数值，单位米。
 *
 * @param zoneId             区域标识
 * @param windowStartMinute  时段起始（UTC 一日内分钟，含）
 * @param windowEndMinute    时段结束（UTC 一日内分钟，不含）
 * @param metric             指标：VISIBILITY 能见度 / CLOUD_BASE 云底
 * @param actualValue        预测实际值，单位米
 * @param requiredValue      最低标准要求值，单位米
 * @param deficit            差额 = 要求值 - 实际值，单位米
 */
public record WeatherViolationDto(
        String zoneId,
        Integer windowStartMinute,
        Integer windowEndMinute,
        String metric,
        Integer actualValue,
        Integer requiredValue,
        Integer deficit) {
}
