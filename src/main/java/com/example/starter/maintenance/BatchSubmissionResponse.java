package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

/**
 * 批量提交响应：逐条返回提交时刻固化的维护标记；itemCount/affectedCount 为实际值。
 *
 * @param requestId      批次请求标识
 * @param submittedAtUtc 提交受理时刻（UTC，全批统一）
 * @param itemCount      批内观测条数（实际值）
 * @param affectedCount  命中维护窗口的条数（实际值）
 * @param items          逐条提交结果，顺序与请求一致
 */
public record BatchSubmissionResponse(
        String requestId,
        Instant submittedAtUtc,
        int itemCount,
        int affectedCount,
        List<StationObservationResponse> items) {
}
