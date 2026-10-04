package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 区段在指定时刻的生效限速诊断视图（只读）。
 * effectiveSpeedKmh 为该时刻所有生效限速令的最低速度；无生效限速令时为 null。
 */
public record EffectiveSpeedView(
        String sectionId,
        Instant at,
        Integer effectiveSpeedKmh,
        List<RestrictionResponse> activeRestrictions) {
}