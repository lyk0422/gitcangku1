package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Cohort;

/**
 * 投放队列视图，含策略与当前统计。
 */
public record CohortView(long cohortId, long campaignId, String code, String firmwareVersion,
                       String region, int regionQuota, int deviceCap, int grayPercent,
                       int deviceCount, String status, int successCount, int monitorRound,
                       int roundSuccess, int roundFailed) {

    public static CohortView of(Cohort cohort) {
        return new CohortView(cohort.id(), cohort.campaignId(), cohort.code(),
                cohort.firmwareVersion(), cohort.region(), cohort.regionQuota(), cohort.deviceCap(),
                cohort.grayPercent(), cohort.deviceCount(), cohort.status().name(),
                cohort.successCount(), cohort.monitorRound(), cohort.roundSuccess(),
                cohort.roundFailed());
    }
}
