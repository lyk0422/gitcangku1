package com.example.starter.blackstart;

import java.time.Instant;
import java.util.List;

/**
 * 黑启动计划查询视图：当前步骤、每步读数与拒绝理由、终止或完成时刻（UTC）。
 */
public record PlanView(
        long planId,
        String status,
        int currentStep,
        Instant createdAt,
        Instant completedAt,
        Instant terminatedAt,
        String terminationReason,
        List<StepView> steps) {

    /**
     * 单步视图。stepNo 为 1~3；succeeded 表示该步是否已成功；
     * attempts 为该步全部确认记录（含被拒绝记录），按记录时刻升序。
     */
    public record StepView(
            int stepNo,
            boolean succeeded,
            Instant succeededAt,
            List<AttemptView> attempts) {
    }

    /**
     * 单次确认记录视图：读数（V/Hz/kW，未提供为 null）、结果与拒绝理由、UTC 记录时刻。
     */
    public record AttemptView(
            String requestId,
            String outcome,
            String deviceStatus,
            Double voltageV,
            Double frequencyHz,
            Double powerKw,
            String rejectReason,
            Instant recordedAt) {
    }
}