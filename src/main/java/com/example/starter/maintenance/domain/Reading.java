package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前累计工时（分钟）
 * @param loadType           负荷类型：决定本读数与前一条之间区间的等效权重（NORMAL=1，HEAVY=2）
 * @param revisionNo         当前修订号，初始 1
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        LoadType loadType,
        int revisionNo) {
}
