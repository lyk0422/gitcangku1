package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Cohort;

/**
 * 投放队列视图。
 */
public record CohortView(long cohortId, long releaseId, String cohortCode, String firmwareVersion,
                         String regionCode, int deviceCap, int canaryPercent, int deviceCount,
                         int successCount, int failedCount, boolean paused) {

    public static CohortView of(Cohort cohort, int deviceCount) {
        return new CohortView(cohort.id(), cohort.releaseId(), cohort.cohortCode(), cohort.firmwareVersion(),
                cohort.regionCode(), cohort.deviceCap(), cohort.canaryPercent(), deviceCount,
                cohort.successCount(), cohort.failedCount(), cohort.paused());
    }
}
