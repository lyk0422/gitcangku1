package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 漂移修正锚点视图（证据查询返回，按采样时刻升序）。
 *
 * @param positionNo          规范化后的位置，从 1 开始
 * @param readingId           锚点读数标识
 * @param sampledAt           锚点读数 UTC 采样时刻
 * @param expectedRevisionNo  期望锚点修订号
 * @param calibratedHours     校准真实累计工时（小时，3 位小数）
 */
public record DriftAnchorView(
        int positionNo,
        String readingId,
        Instant sampledAt,
        int expectedRevisionNo,
        BigDecimal calibratedHours) {
}
