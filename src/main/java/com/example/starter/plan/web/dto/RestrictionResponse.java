package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速令视图（单个版本）。时段为 UTC 左闭右开区间；
 * status 为 ACTIVE / SUPERSEDED / REVOKED；createdAt 为该版本登记时刻。
 */
public record RestrictionResponse(String restrictionKey, int version, String sectionId,
                                  Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                  String status, String operator, Instant createdAt) {
}
