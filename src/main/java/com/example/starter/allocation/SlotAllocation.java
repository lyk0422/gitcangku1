package com.example.starter.allocation;

import java.time.Instant;

/**
 * 接收时隙分配记录，确认时固化预测版本与容量快照。
 *
 * @param id 主键
 * @param requestId 分配请求幂等键
 * @param requestFingerprint 请求参数指纹
 * @param predictionId 固化的预测行 id
 * @param predictionKey 预测业务键快照
 * @param predictionVersion 固化的预测版本
 * @param stationId 接收站 id
 * @param slotStartUtc 时隙起点，UTC
 * @param slotEndUtc 时隙终点，UTC
 * @param capacitySnapshotMbPerMin 容量快照，MB/分钟
 * @param plannedDataMb 计划数据量快照，MB
 * @param status 分配状态
 * @param actualBytes 实际接收字节数，未完成时为 null
 * @param completionRequestId 完成回报幂等键，未完成时为 null
 * @param cancelReason 取消原因，未取消时为 null
 * @param createdAtUtc 创建时间，UTC
 * @param updatedAtUtc 最近变更时间，UTC
 */
public record SlotAllocation(
        long id,
        String requestId,
        String requestFingerprint,
        long predictionId,
        String predictionKey,
        int predictionVersion,
        long stationId,
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
}