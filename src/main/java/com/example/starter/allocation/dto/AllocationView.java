package com.example.starter.allocation.dto;

import java.time.Instant;

import com.example.starter.allocation.AllocationStatus;
import com.example.starter.allocation.SlotAllocation;

/**
 * 时隙分配视图，包含固化的预测版本、容量快照与接收实绩。
 */
public record AllocationView(
        long id,
        String requestId,
        String predictionKey,
        int predictionVersion,
        long stationId,
        String stationCode,
        Instant slotStartUtc,
        Instant slotEndUtc,
        int capacitySnapshotMbPerMin,
        long plannedDataMb,
        AllocationStatus status,
        Long actualBytes,
        String completionRequestId,
        String cancelReason,
        Instant createdAtUtc,
        Instant updatedAtUtc) {

    /**
     * 从实体转换。
     */
    public static AllocationView from(SlotAllocation allocation, String stationCode) {
        return new AllocationView(
                allocation.id(),
                allocation.requestId(),
                allocation.predictionKey(),
                allocation.predictionVersion(),
                allocation.stationId(),
                stationCode,
                allocation.slotStartUtc(),
                allocation.slotEndUtc(),
                allocation.capacitySnapshotMbPerMin(),
                allocation.plannedDataMb(),
                allocation.status(),
                allocation.actualBytes(),
                allocation.completionRequestId(),
                allocation.cancelReason(),
                allocation.createdAtUtc(),
                allocation.updatedAtUtc());
    }
}