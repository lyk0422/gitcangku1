package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 重算链中的单个测量版本摘要。
 *
 * @param versionNo        版本号
 * @param id              版本行 ID
 * @param temperatureC     环境温度（摄氏度字符串）；未记录为 null
 * @param humidityPct      环境湿度（%RH 字符串）；未记录为 null
 * @param coefficientId     补偿系数版本 ID 快照；未补偿为 null
 * @param coefficientVersion 补偿系数版本号；未补偿为 null
 * @param rawReading      原始读数字符串
 * @param computedValue   基础计算值字符串
 * @param compensationValue 补偿值字符串；未补偿为 null
 * @param compensatedValue 补偿后测量值字符串；未补偿为 null
 * @param uncertainty    不确定度字符串；未评估为 null
 * @param status         该版本状态
 * @param passedAfterComp 补偿后是否超规格；未补偿为 null
 * @param createdAt       该版本创建时间（UTC）
 */
public record MeasurementVersionSummary(
        int versionNo,
        long id,
        String temperatureC,
        String humidityPct,
        Long coefficientId,
        Integer coefficientVersion,
        String rawReading,
        String computedValue,
        String compensationValue,
        String compensatedValue,
        String uncertainty,
        String status,
        Boolean passedAfterComp,
        Instant createdAt) {
}
