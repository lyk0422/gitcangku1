package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划限速影响诊断视图（只读，不改变状态）。
 * violation 表示原计划时刻是否违反限速；feasible 表示在运营日内能否通过整体顺延消除违反；
 * requiredShiftMinutes 为所需整体顺延分钟数（无需顺延时为 0，不可行时为 null）。
 */
public record RestrictionImpactView(
        String scheduleKey,
        int normalSpeedKmh,
        int minIntervalMinutes,
        boolean violation,
        boolean feasible,
        Integer requiredShiftMinutes,
        List<SegmentImpactView> segments) {
}
