package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核时效诊断：逐维度对比审核固化版本与当前版本，原审核结果不被改写。
 *
 * @param reviewId   审核记录标识
 * @param conclusion 审核保存时的原结论（CLEAR / BLOCKED / WEATHER_BLOCKED，永不改变）
 * @param stale      true 表示任一维度已偏离固化值（当前查询应视为 STALE）
 * @param dimensions 各维度诊断，固定顺序：ROUTE、AIRSPACE、WEATHER_MINIMA、FORECAST、DUTY
 */
public record ReviewDiagnosticsDto(
        String reviewId,
        String conclusion,
        Boolean stale,
        List<VersionDiagnosticDto> dimensions) {
}
