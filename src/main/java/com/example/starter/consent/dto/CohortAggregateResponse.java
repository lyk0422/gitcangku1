package com.example.starter.consent.dto;

/**
 * 最小群体汇总响应：只包含人数与总和，不包含逐主体值或名单。
 *
 * @param memberCount 群体人数（3-10）
 * @param total       群体数值总和
 */
public record CohortAggregateResponse(int memberCount, long total) {
}
