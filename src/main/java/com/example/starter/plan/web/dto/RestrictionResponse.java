package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速令响应。operator 为 null 表示登记时未提供操作者。
 */
public record RestrictionResponse(String restrictionKey, String sectionId,
                                  Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                  int version, String status, String operator) {
}
