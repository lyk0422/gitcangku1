package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次明细视图：批次本体、最新统计、全部读数、离群（含复核历史）、统计历史与放行快照。
 * 读取为只读操作，不改变任何状态。
 *
 * @param batchKey         业务批次键
 * @param instrumentId     仪器 ID
 * @param targetValue      目标值（十进制字符串）
 * @param allowedDeviation 允许偏差（十进制字符串）
 * @param revision         当前测量修订版本
 * @param status           状态：OPEN / RELEASED
 * @param createdAt        创建时间（UTC）
 * @param latestStats      最新统计记录；尚无读数为 null
 * @param readings         全部读数（按提交顺序）
 * @param outliers         全部离群记录（按检测顺序，含复核历史）
 * @param statsHistory     统计重算历史（按重算顺序）
 * @param releaseSnapshots 放行快照历史（按放行顺序）
 */
public record BatchDetailResponse(
        String batchKey,
        String instrumentId,
        String targetValue,
        String allowedDeviation,
        int revision,
        String status,
        Instant createdAt,
        StatsView latestStats,
        List<ReadingView> readings,
        List<OutlierView> outliers,
        List<StatsView> statsHistory,
        List<SnapshotView> releaseSnapshots) {
}