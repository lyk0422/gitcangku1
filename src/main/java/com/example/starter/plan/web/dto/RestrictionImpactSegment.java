package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单条占用受限速影响的诊断视图。
 * affectedMinutes 为占用窗口与限速窗口并集的重叠分钟数（0 表示不受限速影响）；
 * effectiveMaxSpeedKmh 为该占用时段叠加限速令的最低速度，null 表示无生效限速。
 */
public record RestrictionImpactSegment(String trainNo, String sectionId,
                                       Instant startUtc, Instant endUtc,
                                       long affectedMinutes, Integer effectiveMaxSpeedKmh,
                                       List<RestrictionSnapshotItem> restrictions) {
}