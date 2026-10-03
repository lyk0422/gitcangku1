package com.example.starter.model;

import java.time.Instant;

/**
 * 接收时隙分配。
 *
 * @param id 主键
 * @param requestId 分配幂等请求号
 * @param requestHash 分配请求参数指纹
 * @param predictionId 确认时使用的预测记录ID
 * @param predictionVersion 确认时固化的预测版本快照
 * @param stationId 地面站ID
 * @param slotStartUtc 时隙开始（UTC）
 * @param slotEndUtc 时隙结束（UTC）
 * @param capacityBytesPerMin 确认时容量快照（字节/分钟）
 * @param plannedBytes 确认时计划数据量快照（字节）
 * @param status 状态
 * @param cancelReason 取消原因，未取消为 null
 * @param actualBytes 实际接收字节数，未完成为 null
 * @param completionRequestId 完成回报幂等请求号，未完成为 null
 * @param completionRequestHash 完成回报参数指纹，未完成为 null
 * @param createdAtUtc 创建时间（UTC）
 * @param updatedAtUtc 最后更新时间（UTC）
 */
public record SlotAllocation(
        long id,
        String requestId,
        String requestHash,
        long predictionId,
        int predictionVersion,
        long stationId,
        Instant slotStartUtc,
        Instant slotEndUtc,
        long capacityBytesPerMin,
        long plannedBytes,
        AllocationStatus status,
        String cancelReason,
        Long actualBytes,
        String completionRequestId,
        String completionRequestHash,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}