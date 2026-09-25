package com.example.starter.maintenance.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeValue    当前累计工时（设备登记单位，十进制最多 2 位小数）
 * @param cumulativeMinutes  当前累计工时换算分钟数（四舍五入到最近整数），判定统一口径
 * @param revisionNo         当前修订号，初始 1
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        BigDecimal cumulativeValue,
        long cumulativeMinutes,
        int revisionNo) {
}
