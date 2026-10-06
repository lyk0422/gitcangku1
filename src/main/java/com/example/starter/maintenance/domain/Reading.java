package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前归一累计工时（分钟）；启用量程设备为 wrapCount*M+raw
 * @param revisionNo         当前修订号，初始 1
 * @param raw                量程内原始读数 raw（分钟），0 <= raw < M；未启用量程设备为 null
 * @param wrapCount          已发生回绕次数，非负整数；未启用量程设备为 null
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        Long raw,
        Long wrapCount) {
}
