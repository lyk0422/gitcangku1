package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 区段在某 UTC 时刻的生效限速诊断视图。
 * effectiveSpeedKmh 为该时刻叠加限速令的最低速度；为 null 表示该时刻无生效限速。
 */
public record EffectiveLimitView(String sectionId, Instant at, Integer effectiveSpeedKmh,
                                 List<RestrictionResponse> restrictions) {
}
