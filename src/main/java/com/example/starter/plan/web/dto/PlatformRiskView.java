package com.example.starter.plan.web.dto;

/**
 * 计划站台风险快照视图。时刻为 UTC 毫秒；resolvedAt 为 null 表示风险未解除。
 */
public record PlatformRiskView(String platformCode, int previousLength, int newLength,
                               int consistLength, long markedAt, Long resolvedAt) {
}
