package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏诊断响应：统计 UTC 区间 [fromUtc, toUtc)（左闭右开）内生成的脱敏版本数量，
 * 并返回该区间窗口的时长；数量与时长均返回实际值、要求值与差额（实际 - 要求）。
 *
 * @param actualCount            区间内实际生成的脱敏版本数量
 * @param expectedCount          调用方给定的要求数量
 * @param countDifference        数量差额：actualCount - expectedCount
 * @param actualDurationSeconds  区间窗口实际时长（秒）：toUtc - fromUtc，恒为正数
 * @param expectedDurationSeconds 调用方给定的要求时长（秒）；未提供为 null
 * @param durationDifference     时长差额（秒）：actualDurationSeconds - expectedDurationSeconds；未提供要求值为 null
 * @param fromUtc                区间起点（含，UTC，ISO-8601）
 * @param toUtc                  区间终点（不含，UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionDiagnosticsResponse(
        long actualCount,
        long expectedCount,
        long countDifference,
        long actualDurationSeconds,
        Long expectedDurationSeconds,
        Long durationDifference,
        Instant fromUtc,
        Instant toUtc) {
}
