package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 诊断视图：单个占用段在当前生效限速令下的受影响情况（按原始计划时刻计算）。
 * affectedMinutes 为实际受限运行分钟数（实际值），requiredMinutes 为按最低限速
 * 折算所需分钟数（要求值），deficitMinutes 为二者差额；minLimitSpeedKmh 为受影响
 * 时段的最低限速，无影响时为 null。
 */
public record SegmentImpactView(
        int seq,
        String trainNo,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        long affectedMinutes,
        long requiredMinutes,
        long deficitMinutes,
        Integer minLimitSpeedKmh,
        List<Long> restrictionIds) {
}
