package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 批量禁运设置响应：items 按 observationId 排序，顺序稳定。
 *
 * @param requestId 请求标识
 * @param operator  操作者标识
 * @param setAtUtc  设置完成时刻（UTC）
 * @param items     各簇禁运明细（按 observationId 升序）
 */
public record SetEmbargoResponse(
        String requestId,
        String operator,
        Instant setAtUtc,
        List<EmbargoResponse> items) {
}