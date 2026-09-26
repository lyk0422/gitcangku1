package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 气象限速令响应。状态为 ACTIVE 生效 / REVOKED 已撤销；历史版本行原样返回不改写。
 */
public record RestrictionResponse(String restrictionKey, int version, String sectionId,
                                  Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                  String status, String operator, Instant createdAtUtc) {
}
