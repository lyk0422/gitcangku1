package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 站台容量诊断视图（只读）。基于指定运营日当前仍生效（PUBLISHED）计划的站停快照，
 * 按到发区间左闭右开统计同时占用峰值；到/发为 null 的区间以运营日边界补齐。
 *
 * @param stationId         站点业务 ID
 * @param date              运营日期（Asia/Shanghai 日历日）
 * @param version           当前配置版本
 * @param minDwellMinutes   当前最小停站分钟数（要求值）
 * @param platformCapacity  当前站台容量（要求值）
 * @param publishedCount    生效快照数量（实际值）
 * @param peakConcurrent    同一时刻同时占用站台的峰值列车数（实际值）
 * @param remainingSlots    峰值后剩余容量 = platformCapacity - peakConcurrent（差额，负数表示超卖）
 * @param snapshots         参与统计的生效快照，按到达时刻、计划键、序号稳定排序
 */
public record StationDiagnosticsResponse(String stationId, LocalDate date, int version,
                                         int minDwellMinutes, int platformCapacity,
                                         int publishedCount, int peakConcurrent, int remainingSlots,
                                         List<StationSnapshotView> snapshots) {
}
