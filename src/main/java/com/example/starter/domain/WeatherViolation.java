package com.example.starter.domain;

/**
 * 气象不达标明细：某区域某 UTC 时段内，某项预测指标低于最低标准。
 *
 * @param zoneId             区域标识
 * @param windowStartMinute  时段起始（UTC 一日内分钟，含）
 * @param windowEndMinute    时段结束（UTC 一日内分钟，不含）
 * @param metric             指标：VISIBILITY 能见度 / CLOUD_BASE 云底
 * @param actualValue        预测实际值，单位米
 * @param requiredValue      最低标准要求值，单位米
 */
public record WeatherViolation(String zoneId, int windowStartMinute, int windowEndMinute,
                               String metric, int actualValue, int requiredValue) {

    /** 能见度指标名。 */
    public static final String METRIC_VISIBILITY = "VISIBILITY";
    /** 云底指标名。 */
    public static final String METRIC_CLOUD_BASE = "CLOUD_BASE";

    /** 差额 = 要求值 - 实际值（正数表示低于标准的量，单位米）。 */
    public int deficit() {
        return requiredValue - actualValue;
    }
}
