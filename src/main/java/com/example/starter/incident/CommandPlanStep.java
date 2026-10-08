package com.example.starter.incident;

import java.time.Instant;

/**
 * 指令计划步骤实体，对应 command_plan_steps 表。
 * stepIndex 计划内从 1 连续递增；只能顺序执行、逆序补偿。
 * status 见 {@link PlanStepStatus}：appliedBy/appliedAt 仅 APPLIED/COMPENSATED 有值，
 * compensatedBy/compensatedAt 仅 COMPENSATED 有值。时间均为 UTC。
 */
public record CommandPlanStep(
        long id,
        long planId,
        long incidentId,
        int stepIndex,
        String stepKey,
        String effectDesc,
        String compensationDesc,
        PlanStepStatus status,
        String appliedBy,
        Instant appliedAt,
        String compensatedBy,
        Instant compensatedAt,
        Instant createdAt,
        Instant updatedAt) {
}
