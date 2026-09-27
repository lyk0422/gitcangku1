package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏诊断结果：UTC 左闭右开区间内的实际生成/撤销数量与时长统计，查询不修改状态。
 *
 * @param windowFrom               区间起点（含，UTC）
 * @param windowTo                 区间终点（不含，UTC）
 * @param requiredDurationSeconds  要求时长：区间长度（秒）
 * @param actualCreatedCount       实际生成脱敏版本数
 * @param actualRevokedCount       实际撤销脱敏版本数
 * @param requiredCount            要求数量（查询参数，未提供时为 null）
 * @param countGap                 数量差额（实际生成数 - 要求数量；未提供要求时为 null）
 * @param firstCreatedAtUtc        区间内首个脱敏版本生成时刻（UTC；无则为 null）
 * @param lastCreatedAtUtc         区间内末个脱敏版本生成时刻（UTC；无则为 null）
 * @param actualDurationSeconds    实际时长：末个与首个脱敏版本生成时刻之差（秒；不足两个时为 0）
 * @param durationGapSeconds       时长差额（实际时长 - 要求时长）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionDiagnosticsResponse(
        Instant windowFrom,
        Instant windowTo,
        long requiredDurationSeconds,
        int actualCreatedCount,
        int actualRevokedCount,
        Integer requiredCount,
        Integer countGap,
        Instant firstCreatedAtUtc,
        Instant lastCreatedAtUtc,
        long actualDurationSeconds,
        long durationGapSeconds) {
}
