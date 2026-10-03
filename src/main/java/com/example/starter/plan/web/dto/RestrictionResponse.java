package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 气象限速令视图。时段为 UTC 左闭右开区间；createdAt/updatedAt 为 UTC 毫秒时间戳。
 */
public record RestrictionResponse(String restrictionKey, String sectionId,
                                  Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                  int version, String status, String operator,
                                  long createdAt, long updatedAt) {
}