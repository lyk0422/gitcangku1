package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId         读数标识，设备内唯一
 * @param sampledAt         UTC 采样时刻
 * @param cumulativeMinutes 当前累计工时（分钟，四舍五入的兼容值），随修订/漂移修正更新
 * @param revisionNo        当前修订号，初始 1
 * @param cumulativeHours   当前精确累计工时（小时）；漂移修正后为 0.001 小时精度校准值，
 *                         未漂移修正时由分钟值精确换算（6 位小数）
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        BigDecimal cumulativeHours) {

    public Reading(String equipmentId, String readingId, Instant sampledAt,
                   long cumulativeMinutes, int revisionNo) {
        this(equipmentId, readingId, sampledAt, cumulativeMinutes, revisionNo,
                BigDecimal.valueOf(cumulativeMinutes).divide(HOURS_PER_SIXTY, 6,
                        java.math.RoundingMode.HALF_UP));
    }

    /** 60 分钟 = 1 小时的精确换算常量。 */
    public static final BigDecimal HOURS_PER_SIXTY = BigDecimal.valueOf(60);
}
