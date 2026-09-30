package com.example.starter.pass.api;

import com.example.starter.pass.persist.SlotAllocationPo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 接收时隙（接收计划）视图：用户可见的一个有效过境对应一个站、一份计划及终态原因。
 */
public record SlotView(
        long id,
        String requestId,
        String satelliteCode,
        String stationCode,
        String antennaCode,
        Integer predictionRevisionNo,
        Instant windowStart,
        Instant windowEnd,
        BigDecimal passMinElevationDeg,
        Long plannedBytes,
        BigDecimal stationMinElevationDeg,
        Long stationBytesPerMinute,
        String status,
        String pendingReason,
        Long actualBytes,
        String cancelReason,
        Instant confirmedAt,
        Instant receivedAt,
        Instant cancelledAt
) {

    /** 由持久化行映射；未确认前快照字段可能为空。 */
    public static SlotView from(SlotAllocationPo po) {
        return new SlotView(po.getId(), po.getRequestId(), po.getSatelliteCode(),
                po.getStationCode(), po.getAntennaCode(),
                po.getPredictionId() == null ? null : po.getPredictionRevisionNo(),
                po.getWindowStart(), po.getWindowEnd(),
                po.getPassMinElevationDeg(), po.getPlannedBytes() == 0 ? null : po.getPlannedBytes(),
                po.getStationMinElevationDeg(),
                po.getStationBytesPerMinute() == 0 ? null : po.getStationBytesPerMinute(),
                po.getStatus(), po.getPendingReason(), po.getActualBytes(), po.getCancelReason(),
                po.getConfirmedAt(), po.getReceivedAt(), po.getCancelledAt());
    }
}
