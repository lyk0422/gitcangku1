package com.example.starter.incident;

import java.time.Instant;

/**
 * 步骤补偿尝试实体，对应 command_plan_compensations 表，只追加。
 * attemptNo 为该步骤第几次尝试（从 1）；status 见 {@link CompensationStatus}。
 * SUCCEEDED 时 evidence 非空、failureDiagnostic 为空；FAILED 时反之。
 * actor 为操作时当前指挥人；requestId 为全局请求 id。时间均为 UTC。
 */
public record PlanCompensation(
        long id,
        long planId,
        long stepId,
        int stepIndex,
        int attemptNo,
        CompensationStatus status,
        String evidence,
        String failureDiagnostic,
        String actor,
        String requestId,
        Instant createdAt) {
}
