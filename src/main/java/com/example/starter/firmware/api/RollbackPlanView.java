package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackPlan;

/**
 * 回退计划视图。
 */
public record RollbackPlanView(long planId, String planKey, long sourceReleaseId, String model,
                               String targetVersion, String status, int sampleFloor,
                               int failureThresholdPercent, int monitorRound, Integer pausedHopIndex,
                               int roundSuccess, int roundFailed) {

    public static RollbackPlanView of(RollbackPlan plan) {
        return new RollbackPlanView(plan.id(), plan.planKey(), plan.sourceReleaseId(), plan.model(),
                plan.targetVersion(), plan.status().name(), plan.sampleFloor(),
                plan.failureThresholdPercent(), plan.monitorRound(), plan.pausedHopIndex(),
                plan.roundSuccess(), plan.roundFailed());
    }
}
