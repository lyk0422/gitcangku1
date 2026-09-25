package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养工单。
 *
 * @param workOrderId                工单标识（设备内唯一，由客户端提供）
 * @param equipmentId                所属设备标识
 * @param version                    工单版本号，初始 1，开始/关闭/取消/终止各加一
 * @param status                     工单状态：CREATED 未开始 / IN_PROGRESS 进行中 / CLOSED 已关闭 /
 *                                   CANCELLED 已取消 / TERMINATED 已终止
 * @param baselineReadingId          基线读数标识（建单时冻结）
 * @param baselineRevisionNo         基线读数在建单时的修订号（快照）
 * @param baselineSampledAt          基线读数采样时刻（UTC，快照）
 * @param baselineCumulativeMinutes  基线读数累计工时（分钟，快照）
 * @param windowStart                允许登记读数的 UTC 窗口起点（含）
 * @param windowEnd                  允许登记读数的 UTC 窗口终点（不含）
 * @param lastValidReadingId         工单进行期间最近一条有效读数标识（无则回退为基线读数）
 * @param lastValidSampledAt         最近有效读数采样时刻（UTC）
 * @param lastValidCumulativeMinutes 最近有效读数累计工时（分钟）
 * @param lastValidRevisionNo        最近有效读数修订号
 * @param createdAt                  建单时刻（UTC）
 * @param startedAt                  开始时刻（UTC），未开始为 null
 * @param closedAt                   关闭时刻（UTC），未关闭为 null
 * @param cancelledAt                取消时刻（UTC），未取消为 null
 * @param terminatedAt               终止时刻（UTC），未终止为 null
 * @param terminateReason            终止原因，未终止为 null
 */
public record WorkOrder(
        String workOrderId,
        String equipmentId,
        long version,
        String status,
        String baselineReadingId,
        int baselineRevisionNo,
        Instant baselineSampledAt,
        long baselineCumulativeMinutes,
        Instant windowStart,
        Instant windowEnd,
        String lastValidReadingId,
        Instant lastValidSampledAt,
        Long lastValidCumulativeMinutes,
        Integer lastValidRevisionNo,
        Instant createdAt,
        Instant startedAt,
        Instant closedAt,
        Instant cancelledAt,
        Instant terminatedAt,
        String terminateReason) {

    public static final String CREATED = "CREATED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String CLOSED = "CLOSED";
    public static final String CANCELLED = "CANCELLED";
    public static final String TERMINATED = "TERMINATED";

    public boolean open() {
        return CREATED.equals(status) || IN_PROGRESS.equals(status);
    }
}
