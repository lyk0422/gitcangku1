package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * FAIL 核查的追溯隔离区间 [rangeFrom, rangeTo)。
 * 起点为该仪器上一条 PASS 核查时刻（含），此前无 PASS 时取该仪器最早测量时刻；
 * 终点为本次 FAIL 核查时刻（不含）。只能由核查时刻更晚的新 PASS 核查解除。
 *
 * @param id                 隔离区间 ID（自增）
 * @param checkId            触发隔离的 FAIL 核查记录 ID
 * @param checkKey           触发隔离的 FAIL 核查业务键
 * @param instrumentId       仪器 ID
 * @param rangeFrom          区间起点（UTC，左闭，含）
 * @param rangeTo            区间终点（UTC，右开，不含）
 * @param resolved           是否已被更晚的 PASS 核查解除
 * @param resolvedByCheckId  解除区间的 PASS 核查记录 ID；未解除为 null
 * @param resolvedByCheckKey 解除区间的 PASS 核查业务键；未解除为 null
 * @param resolvedAt         解除时间（UTC）；未解除为 null
 * @param createdAt          区间创建时间（UTC）
 */
public record IsolationInterval(
        long id,
        long checkId,
        String checkKey,
        String instrumentId,
        Instant rangeFrom,
        Instant rangeTo,
        boolean resolved,
        Long resolvedByCheckId,
        String resolvedByCheckKey,
        Instant resolvedAt,
        Instant createdAt) {
}
