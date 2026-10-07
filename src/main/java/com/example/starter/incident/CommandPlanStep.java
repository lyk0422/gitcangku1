package com.example.starter.incident;

import java.time.Instant;

/**
 * 指令计划步骤实体，对应 command_plan_steps 表。
 * seq 为执行顺序（从 1 递增）：执行按 seq 正序，补偿按 seq 逆序。
 * appliedBy/appliedAt 仅 APPLIED/COMPENSATED 有值；compensatedBy/compensatedAt 仅 COMPENSATED 有值。
 * 时间均为 UTC。
 */
public record CommandPlanStep(
        long id,
        long planId,
        String stepKey,
        int seq,
        String effectText,
        String compensationText,
        PlanStepStatus status,
        String appliedBy,
        Instant appliedAt,
        String compensatedBy,
        Instant compensatedAt,
        Instant createdAt) {
}
