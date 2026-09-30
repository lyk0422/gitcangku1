package com.example.starter.pass.api;

import com.example.starter.pass.persist.PassPredictionPo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 预测版本视图。
 */
public record PredictionView(
        long id,
        String satelliteCode,
        int revisionNo,
        Instant visibleStart,
        Instant visibleEnd,
        BigDecimal minElevationDeg,
        long plannedBytes,
        boolean superseded,
        Instant createdAt
) {

    /** 由持久化行映射。 */
    public static PredictionView from(PassPredictionPo po) {
        return new PredictionView(po.getId(), po.getSatelliteCode(), po.getRevisionNo(),
                po.getVisibleStart(), po.getVisibleEnd(), po.getMinElevationDeg(),
                po.getPlannedBytes(), po.isSuperseded(), po.getCreatedAt());
    }
}
