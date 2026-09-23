package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时表更换记录：旧表 CLOSED、新表 ACTIVE 的链式快照。
 *
 * @param replacementKey         更换记录唯一标识（全局唯一）
 * @param equipmentId            所属设备标识
 * @param oldMeterKey            被关闭的旧表 meterKey
 * @param newMeterKey            新启用的表 meterKey（全局唯一）
 * @param oldLastReadingId       更换时旧表最后一条读数标识
 * @param oldLastReadingVersion  更换时旧表最后一条读数的修订号（客户端提交并校验）
 * @param finalRawHours          旧表申报最终原始读数（分钟），非负且不小于旧表最后有效读数
 * @param initialRawHours        新表起始原始读数（分钟），非负
 * @param offsetHours            新表冻结的虚拟工时偏移（分钟）
 * @param requestId              更换请求的 requestId
 * @param createdAt              更换登记时刻（UTC）
 */
public record MeterReplacement(
        String replacementKey,
        String equipmentId,
        String oldMeterKey,
        String newMeterKey,
        String oldLastReadingId,
        int oldLastReadingVersion,
        long finalRawHours,
        long initialRawHours,
        long offsetHours,
        String requestId,
        Instant createdAt) {
}
