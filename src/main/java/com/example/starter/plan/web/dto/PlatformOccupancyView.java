package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站台到发占用视图（站台管理方视角，仅含所属计划已发布的生效占用）。
 */
public record PlatformOccupancyView(String platformId, String scheduleKey, String trainNo,
                                    String sectionId, Instant startUtc, Instant endUtc,
                                    int consistLengthMeters, String powerSystem,
                                    boolean accessibleRequired, int capabilityVersion) {
}
