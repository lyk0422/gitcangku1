package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 隔离区间响应。区间为 UTC 左闭右开 [rangeFrom, rangeTo)。
 *
 * @param id                隔离区间 ID
 * @param checkKey          触发隔离的 FAIL 核查键
 * @param instrumentId      仪器 ID
 * @param rangeFrom         区间起点（UTC，含）
 * @param rangeTo           区间终点（UTC，不含）
 * @param resolved          是否已解除
 * @param resolvedByCheckKey 解除该区间的更晚 PASS 核查键；未解除为 null
 * @param resolvedAt        解除时间（UTC）；未解除为 null
 * @param createdAt         区间创建时间（UTC）
 */
public record IsolationIntervalResponse(
        long id,
        String checkKey,
        String instrumentId,
        Instant rangeFrom,
        Instant rangeTo,
        boolean resolved,
        String resolvedByCheckKey,
        Instant resolvedAt,
        Instant createdAt) {
}
