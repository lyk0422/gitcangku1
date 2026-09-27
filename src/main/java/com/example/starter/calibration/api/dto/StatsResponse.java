package com.example.starter.calibration.api.dto;

/**
 * 批次统计快照。无计入读数时 mean/min/max 为 null。
 *
 * @param includedCount 计入统计的读数条数
 * @param excludedCount 已排除的读数条数
 * @param meanValue     计入读数计算值的均值（HALF_UP 12 位，十进制字符串）；无计入读数时为 null
 * @param minValue      计入读数计算值的最小值（十进制字符串）；无计入读数时为 null
 * @param maxValue      计入读数计算值的最大值（十进制字符串）；无计入读数时为 null
 */
public record StatsResponse(
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue) {
}
