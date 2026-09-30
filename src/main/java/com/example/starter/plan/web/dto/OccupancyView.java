package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用视图，起止为 UTC 时刻，区间左闭右开。
 *
 * <p>普通区段占用时站台相关字段为 null；站台到发占用时携带编组三要素，
 * 已发布计划另含发布当时固化的 capabilityVersion（草稿该字段为 null）。
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc,
                            String platformId, Integer consistLengthMeters, String powerSystem,
                            Boolean accessibleRequired, Integer capabilityVersion) {
}
