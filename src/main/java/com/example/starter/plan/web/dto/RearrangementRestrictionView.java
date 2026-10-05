package com.example.starter.plan.web.dto;

/**
 * 重排引用的限速令快照视图：固化使用的限速令版本与原计划受影响分钟数。
 */
public record RearrangementRestrictionView(String restrictionKey, int restrictionVersion,
                                           String sectionId, int maxSpeedKmh,
                                           long affectedMinutes) {
}
