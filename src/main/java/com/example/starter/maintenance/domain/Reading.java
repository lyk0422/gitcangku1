package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId      所属设备标识
 * @param readingId        读数标识，设备内唯一
 * @param meterKey         所属工时表标识
 * @param sampledAt        UTC 采样时刻
 * @param rawHours         表内原始工时读数（小时），非负
 * @param virtualHours     跨表连续虚拟工时（小时）
 * @param revisionNo       当前修订号，初始 1
 */
public record Reading(
        String equipmentId,
        String readingId,
        String meterKey,
        Instant sampledAt,
        BigDecimal rawHours,
        BigDecimal virtualHours,
        int revisionNo) {

    /** 虚拟工时换算为非负整数分钟（四舍五入），用于按分钟的保养周期判定。 */
    public long virtualMinutes() {
        return virtualHours.multiply(BigDecimal.valueOf(60))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }
}
