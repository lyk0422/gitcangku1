package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 工时表更换记录视图。
 *
 * @param replacementKey         更换记录唯一标识
 * @param oldMeterKey            被关闭的旧表 meterKey
 * @param newMeterKey            新启用的表 meterKey
 * @param oldLastReadingId       更换时旧表最后一条读数标识
 * @param oldLastReadingVersion  更换时旧表最后一条读数的修订号
 * @param finalRawHours          旧表申报最终原始读数（分钟）
 * @param initialRawHours        新表起始原始读数（分钟）
 * @param offsetHours            新表冻结的虚拟工时偏移（分钟）
 * @param createdAt              更换登记时刻（UTC）
 */
public record ReplacementView(
        String replacementKey,
        String oldMeterKey,
        String newMeterKey,
        String oldLastReadingId,
        int oldLastReadingVersion,
        long finalRawHours,
        long initialRawHours,
        long offsetHours,
        Instant createdAt) {
}
