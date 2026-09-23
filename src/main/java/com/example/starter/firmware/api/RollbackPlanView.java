package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.RollbackPlan;

/**
 * 回退计划视图。
 */
public record RollbackPlanView(long planId, String planKey, long sourceReleaseId, String targetVersion,
                               String status, int currentHop, int maxHop,
                               int sampleFloor, int failureThresholdPercent,
                               int currentRound, int roundSuccess, int roundFailed) {

    public static RollbackPlanView of(RollbackPlan plan) {
        return new RollbackPlanView(plan.id(), plan.planKey(), plan.sourceReleaseId(), plan.targetVersion(),
                plan.status().name(), plan.currentHop(), plan.maxHop(),
                plan.sampleFloor(), plan.failureThresholdPercent(),
                plan.currentRound(), plan.roundSuccess(), plan.roundFailed());
    }
}
