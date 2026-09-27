package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 设备能源隔离诊断视图：当前令状态、锁定点实际/要求/差额数量、实际生效时长与确认/解除比例，
 * 以及左闭右开时间区间 [windowFrom, windowTo) 内的事件与被拒读数明细。查询不修改状态。
 *
 * @param equipmentId               设备标识
 * @param version                   设备当前版本号
 * @param equipmentStatus           设备状态 ACTIVE/DEACTIVATED
 * @param currentOrderId            当前活动隔离令标识；无活动令为 null
 * @param orderStatus               当前活动令状态 PENDING/EFFECTIVE；无活动令为 null
 * @param requiredLockPoints        锁定点要求数量（无令为 0）
 * @param confirmedLockPoints       双角色确认完成点数（实际值）
 * @param unconfirmedLockPoints     尚未双角色确认完成点数（差额 = 要求 - 实际）
 * @param releasedLockPoints        已解除点数（实际值）
 * @param unreleasedLockPoints      尚未解除点数（差额 = 要求 - 实际）
 * @param confirmedRatio            确认比例 = 实际/要求（无要求点时为 0）
 * @param releasedRatio             解除比例 = 实际/要求（无要求点时为 0）
 * @param effectiveDurationSeconds  实际生效时长（秒，UTC）：EFFECTIVE 为 now-effectiveAt，
 *                                  RELEASED 令查看历史时为 releasedAt-effectiveAt；PENDING/无令为 0
 * @param windowFrom                统计区间起点（含，UTC）；不限为 null
 * @param windowTo                  统计区间终点（不含，UTC）；不限为 null
 * @param eventsInWindow            区间内隔离事件数（实际值）
 * @param rejectedReadingsInWindow  区间内被拒读数数（实际值）
 * @param events                    区间内事件明细，按事件序号升序
 * @param rejectedReadings          区间内被拒读数明细，按拒绝序号升序
 */
public record IsolationDiagnosticsResponse(
        String equipmentId,
        long version,
        String equipmentStatus,
        Long currentOrderId,
        String orderStatus,
        int requiredLockPoints,
        int confirmedLockPoints,
        int unconfirmedLockPoints,
        int releasedLockPoints,
        int unreleasedLockPoints,
        double confirmedRatio,
        double releasedRatio,
        long effectiveDurationSeconds,
        Instant windowFrom,
        Instant windowTo,
        long eventsInWindow,
        long rejectedReadingsInWindow,
        List<IsolationEventView> events,
        List<RejectedReadingView> rejectedReadings) {
}
