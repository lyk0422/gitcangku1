package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 漂移修正锚点视图（按采样时刻规范化排序后）。
 *
 * @param seq                锚点序号，按读数采样时刻升序从 1 开始
 * @param readingId          锚点读数标识
 * @param sampledAt          锚点读数的 UTC 采样时刻
 * @param expectedVersion    提交时锚点读数的期望修订号
 * @param cumulativeMillis   经校准的真实累计工时（毫秒），精确到 0.001 小时
 * @param cumulativeHours    经校准的真实累计工时（小时，固定 3 位小数的十进制字符串）
 */
public record DriftAnchorView(
        int seq,
        String readingId,
        Instant sampledAt,
        int expectedVersion,
        long cumulativeMillis,
        String cumulativeHours) {
}
