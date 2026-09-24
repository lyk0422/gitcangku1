package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * FAIL 核查引入的追溯隔离区间，UTC 左闭右开 [rangeFrom, rangeTo)。
 * rangeFrom 为该仪器上一条 PASS 核查时刻；此前无 PASS 时取该仪器最早测量时刻。
 * resolvedByCheckId 非空表示已被更晚的 PASS 核查解除。
 *
 * @param id                隔离区间 ID（自增）
 * @param checkId           触发隔离的 FAIL 核查记录 ID
 * @param instrumentId      仪器 ID
 * @param rangeFrom         区间起点（UTC，含）
 * @param rangeTo           区间终点（UTC，不含），即 FAIL 核查时刻
 * @param resolvedByCheckId 解除该区间的 PASS 核查记录 ID；null 表示未解除
 * @param resolvedAt        解除时间（UTC）；未解除为 null
 * @param createdAt         区间创建时间（UTC）
 */
public record IsolationInterval(
        long id,
        long checkId,
        String instrumentId,
        Instant rangeFrom,
        Instant rangeTo,
        Long resolvedByCheckId,
        Instant resolvedAt,
        Instant createdAt) {
}
