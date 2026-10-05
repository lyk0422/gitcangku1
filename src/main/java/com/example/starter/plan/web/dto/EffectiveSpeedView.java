package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 区段在指定时刻的生效限速诊断视图。
 * effectiveSpeedKmh 为该时刻全部生效限速的最低值；为 null 表示该时刻无生效限速（不限速）。
 */
public record EffectiveSpeedView(String sectionId, Instant at, Integer effectiveSpeedKmh,
                                 List<RestrictionResponse> activeRestrictions) {
}
