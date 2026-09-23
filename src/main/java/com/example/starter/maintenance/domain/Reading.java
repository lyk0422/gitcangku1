package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param meterKey           读数所属工时表；虚拟工时 = cumulativeMinutes + 表 offset
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前原始累计工时（分钟）
 * @param revisionNo         当前修订号，初始 1
 */
public record Reading(
        String equipmentId,
        String readingId,
        String meterKey,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo) {
}
