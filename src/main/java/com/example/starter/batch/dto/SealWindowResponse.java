package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 包装明细的 UTC 时间窗统计：区间左闭右开 [from, to)。
 *
 * @param from            起点（含），ISO-8601 UTC；未指定时为 null
 * @param to              终点（不含），ISO-8601 UTC；未指定时为 null
 * @param durationSeconds 窗口时长（秒，实际值 = to - from）；两端未同时指定时为 null
 * @param sealEventCount  窗口内封箱事件（含已作废的历史版本）条数
 * @param sealedQuantity  窗口内封箱事件的数量之和（实际值）
 */
public record SealWindowResponse(
        Instant from,
        Instant to,
        Long durationSeconds,
        long sealEventCount,
        long sealedQuantity
) {
}
