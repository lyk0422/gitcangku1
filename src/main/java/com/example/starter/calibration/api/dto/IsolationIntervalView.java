package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 隔离区间只读视图。
 *
 * @param checkKey           触发隔离的 FAIL 核查业务键
 * @param instrumentId       仪器 ID
 * @param rangeFrom          区间起点（UTC，左闭，含）
 * @param rangeTo            区间终点（UTC，右开，不含）
 * @param resolved           是否已被更晚 PASS 解除
 * @param resolvedByCheckKey 解除区间的 PASS 核查业务键；未解除为 null
 * @param resolvedAt         解除时间（UTC）；未解除为 null
 * @param affectedKeys       该 FAIL 引入 SUSPECT 标记的已放行测量键（历史保留，含已恢复者）
 */
public record IsolationIntervalView(
        String checkKey,
        String instrumentId,
        Instant rangeFrom,
        Instant rangeTo,
        boolean resolved,
        String resolvedByCheckKey,
        Instant resolvedAt,
        java.util.List<String> affectedKeys) {
}
