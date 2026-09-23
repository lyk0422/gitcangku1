package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CohortRegion;

/**
 * 区域配额视图。
 */
public record CohortRegionView(long regionId, long releaseId, String regionCode, int quota) {

    public static CohortRegionView of(CohortRegion region) {
        return new CohortRegionView(region.id(), region.releaseId(), region.regionCode(), region.quota());
    }
}
