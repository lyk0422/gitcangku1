package com.example.starter.api.dto;

/**
 * 单个让渡结果（走廊创建响应内返回，诊断用）。
 * 时间均为 epoch 毫秒（UTC），区间左闭右开。
 *
 * @param occupancyId      被让渡的原占用标识
 * @param reviewId         固化的原审查标识
 * @param routeVersion     固化的原航线版本
 * @param airspaceVersion  固化的原空域版本
 * @param overlapStartUtc  占用与走廊实际重叠起点（含）
 * @param overlapEndUtc    占用与走廊实际重叠终点（不含）
 * @param overlapDurationMillis 实际让渡时长（毫秒）= overlapEndUtc - overlapStartUtc
 * @param reason           让渡原因
 */
public record YieldOutcomeDto(String occupancyId, String reviewId, int routeVersion,
                              long airspaceVersion, long overlapStartUtc, long overlapEndUtc,
                              long overlapDurationMillis, String reason) {
}
