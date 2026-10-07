package com.example.starter.incident;

import java.time.Instant;

/**
 * 指令计划补偿证据实体，对应 command_plan_compensations 表。
 * 每次补偿尝试均记录一条：outcome 为 SUCCESS（步骤置 COMPENSATED）或
 * FAILED（仅保存诊断，步骤保持 APPLIED，允许新 requestId 重试）。
 * diagnostics 仅 FAILED 有值。时间为 UTC。
 */
public record CommandPlanCompensation(
        long id,
        long planId,
        long stepId,
        String stepKey,
        String evidence,
        String outcome,
        String diagnostics,
        String actor,
        Instant createdAt) {
}
