package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 受影响计划处理结果视图：登记时的原计划键、处理结果、改签后继键（撤销时为 null）
 * 及与封锁相交的原占用快照（原区段、原时段，不受后续改签影响）。
 */
public record AffectedPlanView(String scheduleKey, String action, String successorScheduleKey,
                               List<OccupancyView> slots) {
}
