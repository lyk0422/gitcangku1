package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 工时读数当前值。
 *
 * @param equipmentId        所属设备标识
 * @param readingId          读数标识，设备内唯一
 * @param sampledAt          UTC 采样时刻
 * @param cumulativeMinutes  当前累计工时（分钟），毫秒值四舍五入视图
 * @param cumulativeMillis   当前累计工时（毫秒），规范精确值；分钟读数按 ×60000 换算，
 *                           漂移修正读数精确到 0.001 小时（3600 毫秒）
 * @param revisionNo         当前修订号，初始 1
 */
public record Reading(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        long cumulativeMillis,
        int revisionNo) {
}
